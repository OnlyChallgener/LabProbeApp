"""Project-scoped game rounds and quality samples retained by the independent HUB."""
import copy,hashlib,json,logging,math,re,threading,time
from datetime import datetime,timedelta
from .commands import UFCommand
from .daily import CHINA
from .identifiers import mac_key
from .projection import station_snapshots
from .devices import DeviceRegistry
from .telemetry import USER_LIST

LOG=logging.getLogger('shinya-game-report')
LINE=re.compile(r'\[(\d{10})\]:([0-9a-fA-F:]{12,17})[ \t]+(\S+)[ \t]+(\S+)[ \t]+(\S+)[ \t]+([^\n]+?)[ \t]+(\S+)[ \t]*$',re.M)
UPLOAD_LINE=re.compile(r'^(?:air_fc[ \t]+)?(\d{10})[ \t]+([0-9a-fA-F:]{12,17})[ \t]+(\S+)[ \t]+(\S+)[ \t]+(\S+)[ \t]+([^\n]+?)[ \t]+(\S+)[ \t]*$',re.M)

def numeric(value):
    try:v=float(value);return v if math.isfinite(v) else None
    except (TypeError,ValueError):return None

def record_channel_action(store,key,band,result,trigger='channel-optimize'):
    """Record a completed manual optimization, or an observed automatic switch."""
    if not isinstance(result,dict):return
    before=result.get('beforeChannel');after=result.get('afterChannel')
    if before is None or after is None:return
    switched=str(before)!=str(after)
    if not switched and trigger not in {'channel-optimize','ai-noise','game-environment'}:return
    at=numeric(result.get('optimizedAt'))
    if at is None and not switched:return
    if at is None:raise ValueError('优化记录时间无效')
    event={'time':at/1000,'mac':'','ip':'','kind':'channel_optimized' if switched else 'channel_checked','app':'','name':'',
           'band':band,'before':str(before),'after':str(after),'trigger':trigger}
    # Trigger is provenance, not another action when a caller retries recording.
    identity={k:v for k,v in event.items() if k!='trigger'}
    eid=hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()
    with store.connect() as db:
        db.execute('''CREATE TABLE IF NOT EXISTS game_events(
            tenant TEXT,project TEXT,sn TEXT,id TEXT,observed REAL,value TEXT,
            PRIMARY KEY(tenant,project,sn,id))''')
        db.execute('INSERT OR IGNORE INTO game_events VALUES(?,?,?,?,?,?)',
            (key.tenant,key.project,key.sn,eid,at/1000,json.dumps(event,ensure_ascii=False)))

class GameReports:
    def __init__(self,store,registry,catalog=None):
        self.store=store;self.registry=registry;self.catalog=catalog;self.stop=threading.Event();self.thread=None
        with store.connect() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS game_rounds(
              tenant TEXT,project TEXT,sn TEXT,uuid TEXT,mac TEXT,ip TEXT,app TEXT,name TEXT,
              start REAL,end REAL,servers TEXT,PRIMARY KEY(tenant,project,sn,uuid));
              CREATE TABLE IF NOT EXISTS game_events(
              tenant TEXT,project TEXT,sn TEXT,id TEXT,observed REAL,value TEXT,
              PRIMARY KEY(tenant,project,sn,id));
              CREATE TABLE IF NOT EXISTS game_quality(
              tenant TEXT,project TEXT,sn TEXT,uuid TEXT,source TEXT,sequence INTEGER,
              observed REAL,value REAL,PRIMARY KEY(tenant,project,sn,uuid,source,sequence));
              CREATE TABLE IF NOT EXISTS game_feedback(
              tenant TEXT,project TEXT,sn TEXT,uuid TEXT,observed REAL,value TEXT);
              CREATE TABLE IF NOT EXISTS game_signals(
              tenant TEXT,project TEXT,sn TEXT,uuid TEXT,observed REAL,rssi REAL,
              PRIMARY KEY(tenant,project,sn,uuid,observed));''')

    def ingest(self,key,body):
        if body.get('source')!='firmware.rcgame' or body.get('error'):raise ValueError('固件游戏记录无效')
        now=numeric(body.get('observedAt'))
        if now is None or not time.time()-86400<=now<=time.time()+300:raise ValueError('固件游戏记录时间无效')
        scope=(key.tenant,key.project,key.sn)
        names={r['id']:r.get('showName',r['id']) for r in self.catalog.official+self.catalog.extra} if self.catalog else {}
        with self.store.connect() as db:
            # Explicit firmware start/leave events define rounds; environment
            # probes and unrelated usage never become invented game rounds.
            text=str(body.get('events',''))[-131072:]
            events=sorted([m.groups() for pattern in (LINE,UPLOAD_LINE) for m in pattern.finditer(text)],key=lambda m:int(m[0]))
            def identity(event):
                normalized={k:event.get(k) for k in ('time','mac','ip','kind','app','name')}
                return hashlib.sha256(json.dumps(normalized,sort_keys=True).encode()).hexdigest()
            previous={identity(json.loads(r['value'])) for r in db.execute(
                'SELECT value FROM game_events WHERE tenant=? AND project=? AND sn=? AND observed>=?',(*scope,now-14*86400))}
            for fields in events:
                epoch=float(fields[0]);mac=mac_key(fields[1]);ip,kind,app,name,servers=fields[2:7]
                if not mac or epoch<now-14*86400 or epoch>now+300:continue
                # The same native event can occur in the status log and its
                # upload copy. Source prefixes do not create another action.
                event={'time':epoch,'mac':mac,'ip':ip,'kind':kind,'app':app,'name':name}
                eid=identity(event)
                if eid in previous:continue
                previous.add(eid)
                db.execute('INSERT INTO game_events VALUES(?,?,?,?,?,?)',(*scope,eid,epoch,json.dumps(event,ensure_ascii=False)))
                if kind=='play' and re.fullmatch(r'[34]-\d+-\d+-\d+',app):
                    app='-'.join(app.split('-')[:3])+'-0'
                    uuid=hashlib.sha256(('\0'.join(scope)+(mac+app+str(epoch))).encode()).hexdigest()
                    # Another real play event on the same terminal closes an
                    # unfinished preceding round; overlapping repeated events
                    # are deduplicated by the firmware event ID above.
                    db.execute('UPDATE game_rounds SET end=? WHERE tenant=? AND project=? AND sn=? AND mac=? AND end IS NULL',(epoch,*scope,mac))
                    db.execute('INSERT OR IGNORE INTO game_rounds VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                        (*scope,uuid,mac,ip,app,names.get(app,app),epoch,None,servers))
                elif kind=='leave_game':
                    db.execute('UPDATE game_rounds SET end=? WHERE tenant=? AND project=? AND sn=? AND mac=? AND end IS NULL AND start<=?',(epoch,*scope,mac,epoch))
            rounds=db.execute('SELECT * FROM game_rounds WHERE tenant=? AND project=? AND sn=? AND end IS NULL',scope).fetchall()
            cache=self.store.cached(*scope,DeviceRegistry.resource(USER_LIST))
            stations=cache['value'].get('list',[]) if cache and 0<=now-cache['observedAt']<=30 else []
            probes=body.get('probes',[])
            if isinstance(probes,dict):probes=list(probes.values())
            for row in rounds:
                station=next((s for s in stations if mac_key(s.get('mac'))==row['mac'] and s.get('userIp')==row['ip']),{})
                rssi=numeric(station.get('rssi'))
                if rssi is not None and -110<=rssi<0:
                    db.execute('INSERT OR IGNORE INTO game_signals VALUES(?,?,?,?,?,?)',(*scope,row['uuid'],cache['observedAt'],rssi))
                for probe in probes:
                    if not isinstance(probe,dict):continue
                    target=probe.get('ipv4');source='sta' if target==row['ip'] else 'net' if target in row['servers'].split(',') else None
                    seq=numeric(probe.get('sequence'));latency=numeric(probe.get('latency'))
                    # 65535 is the firmware timeout sentinel, never 65 seconds.
                    if source is None or seq is None or latency is None or not 0<=latency<=65535:continue
                    db.execute('INSERT OR IGNORE INTO game_quality VALUES(?,?,?,?,?,?,?,?)',
                        (*scope,row['uuid'],source+':'+str(target),int(seq),now,None if latency==65535 else latency))
            for table,field in [('game_rounds','start'),('game_events','observed'),('game_quality','observed'),('game_feedback','observed'),('game_signals','observed')]:
                db.execute(f'DELETE FROM {table} WHERE tenant=? AND project=? AND sn=? AND {field}<?',(*scope,now-14*86400))

    def sync_once(self,key):
        with self.registry.device_operation(key) as driver:
            value=driver.execute(UFCommand('dev_sta','get','shinya_game',{'action':'telemetry'}))
        self.ingest(key,value)

    def start(self,keys):
        def collect():
            while not self.stop.is_set():
                for key in keys:
                    if self.stop.is_set():break
                    try:self.sync_once(key)
                    except Exception as error:LOG.warning('Game records pending for %s: %s',key.sn,type(error).__name__)
                self.stop.wait(15)
        self.thread=threading.Thread(target=collect,name='shinya-game-reports',daemon=True);self.thread.start()

    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=3)

    def finish(self,key):
        with self.store.connect() as db:
            db.execute('UPDATE game_rounds SET end=? WHERE tenant=? AND project=? AND sn=? AND end IS NULL',(time.time(),key.tenant,key.project,key.sn))

    def list(self,key,include_active=True):
        with self.store.connect() as db:
            rounds=db.execute('SELECT * FROM game_rounds WHERE tenant=? AND project=? AND sn=? AND start>=? ORDER BY start DESC LIMIT 300',
                (key.tenant,key.project,key.sn,time.time()-7*86400)).fetchall()
        project=next(p for p in self.store.projects(key.tenant) if p['projectId']==key.project)
        stations={mac_key(r['mac']):r for r in station_snapshots(self.store,key.tenant,project) if r.get('sn')==key.sn}
        return [self._row(key,r,stations.get(r['mac'],{})) for r in rounds
                if (r['end'] is None and include_active) or (r['end'] is not None and r['end']-r['start']>=300)]

    def _row(self,key,row,station):
        with self.store.connect() as db:
            samples=db.execute('SELECT source,sequence,observed,value FROM game_quality WHERE tenant=? AND project=? AND sn=? AND uuid=? ORDER BY observed',
                (key.tenant,key.project,key.sn,row['uuid'])).fetchall()
        remote=[r for r in samples if r['source'].startswith('net:')]
        successful=[r['value'] for r in remote if r['value'] is not None]
        avg=sum(successful)/len(successful) if successful else None
        loss=sum(r['value'] is None for r in remote)*100/len(remote) if remote else None
        start=float(row['start']);end=float(row['end'] or time.time())
        return {**copy.deepcopy(station),'uuid':row['uuid'],'reportId':row['uuid'],'mac':row['mac'],'sn':key.sn,
                'name':station.get('name') or station.get('hostName') or row['mac'],'hostName':station.get('hostName',''),
                'gameName':row['name'],'appName':row['name'],'appId':row['app'],'appIconLink':None,
                'optimizeStartTime':int(start*1000),'optimizeEndTime':int(end*1000),'gameDuration':max(0,int(end-start)),
                'inGaming':1 if row['end'] is None else 0,'netArea':None,'avgDelay':avg,'lossRate':loss,
                'source':'hub-firmware-rounds','measurement':'router-to-active-server-ICMP',
                'delayBad':100,'delayGood':40,'lossBad':3,'lossGood':1.7,'jitterRate':None}

    def detail(self,key,uuid):
        rows=self.list(key)
        value=next((r for r in rows if r['uuid']==str(uuid)),None)
        if value is None:raise LookupError('当前项目中找不到该游戏报告')
        with self.store.connect() as db:
            samples=db.execute('SELECT source,observed,value FROM game_quality WHERE tenant=? AND project=? AND sn=? AND uuid=? ORDER BY observed',
                (key.tenant,key.project,key.sn,str(uuid))).fetchall()
            signals=db.execute('SELECT observed,rssi FROM game_signals WHERE tenant=? AND project=? AND sn=? AND uuid=? ORDER BY observed',
                (key.tenant,key.project,key.sn,str(uuid))).fetchall()
        grouped={}
        for sample in samples:grouped.setdefault(sample['observed'],{'net':[],'sta':[]})[sample['source'].split(':',1)[0]].append(sample['value'])
        times=sorted(grouped)
        def mean(rows):
            valid=[v for v in rows if v is not None]
            return sum(valid)/len(valid) if valid else None
        remote=[mean(grouped[t]['net']) for t in times];terminal=[mean(grouped[t]['sta']) for t in times]
        total=[a+b if a is not None and b is not None else None for a,b in zip(remote,terminal)]
        return {**value,'timeList':[datetime.fromtimestamp(t,CHINA).strftime('%H:%M:%S') for t in times],
                'apNetDelayList':remote,'staDelayList':terminal,
                'totalDelayList':total,'rssiList':[s['rssi'] for s in signals],
                'rssiTimeList':[datetime.fromtimestamp(s['observed'],CHINA).strftime('%H:%M:%S') for s in signals],'keyEventList':[],
                'accuracyLevel':-1,'information':-1,'canCall':-1,'gamePriority':None,
                'weakSignalGuard':None,'staName':value['name']}

    def channel_action(self,key,band,result):
        record_channel_action(self.store,key,band,result)

    def acceleration_statistics(self,key):
        now=datetime.now(CHINA);today=now.replace(hour=0,minute=0,second=0,microsecond=0).timestamp()
        since=(now.replace(hour=0,minute=0,second=0,microsecond=0)-timedelta(days=6)).timestamp()
        with self.store.connect() as db:events=db.execute('SELECT observed,value FROM game_events WHERE tenant=? AND project=? AND sn=? AND observed>=? AND observed<=?',(key.tenant,key.project,key.sn,since,now.timestamp())).fetchall()
        categories={'channel_optimized':'antiBother','channel_checked':'antiBother','add_flowctrl':'antiOccupy','add_air_flowctrl':'antiOccupy','add_group_flowctrl':'antiOccupy'}
        counts={name:set() for name in ('antiBother','antiOccupy')}
        for row in events:
            event=json.loads(row['value']);category=categories.get(event['kind'])
            if category:counts[category].add((row['observed'],event.get('mac',''),event.get('band','')))
        result={name:{'todayCnt':sum(v[0]>=today for v in rows),'historyCnt':len(rows),'unit':'次'} for name,rows in counts.items()}
        # Daemon startup / low RSSI observations are not anti-shake actions.
        # Its kernel event-type mapping still requires a real action sample.
        result['antiShake']={'todayCnt':None,'historyCnt':None,'unit':'次'}
        return {**result,'source':'hub-observed-firmware-actions'}

    def statistics(self,key):
        rows=self.list(key);today=datetime.now(CHINA).date();monday=today-timedelta(days=today.weekday())
        week=[r for r in rows if datetime.fromtimestamp(r['optimizeStartTime']/1000,CHINA).date()>=monday]
        times={}
        for r in week:times[r['gameName']]=times.get(r['gameName'],0)+r['gameDuration']
        best=min((r['avgDelay'] for r in week if r['avgDelay'] is not None),default=None)
        return {'reportNum':sum(r['inGaming']==0 for r in rows),'reportCount':sum(r['inGaming']==0 for r in rows),
                'mostGameTime':max(times.values(),default=0)/60,'mostGameIcon':None,'minDelay':best,
                'minLossRate':min((r['lossRate'] for r in week if r['lossRate'] is not None),default=None),
                'bestServerLocation':None,'source':'hub-firmware-rounds'}

    def logs(self,key):
        with self.store.connect() as db:
            events=db.execute('SELECT observed,value FROM game_events WHERE tenant=? AND project=? AND sn=? AND observed>=? ORDER BY observed DESC LIMIT 100',
                (key.tenant,key.project,key.sn,time.time()-48*3600)).fetchall()
        words={'play':'开始游戏，加速中','leave_game':'离开了游戏','add_flowctrl':'【AI流控】已优先保障游戏流量','del_flowctrl':'已解除大流量抑制',
               'add_group_flowctrl':'【AI流控】已开启空口保障','add_air_flowctrl':'【AI流控】已开启空口保障',
               'clear_group_flowctrl':'已解除空口保障','del_air_flowctrl':'已解除空口保障','lowrssi_play':'游戏终端信号较弱'}
        rows=[]
        for row in events:
            event=json.loads(row['value']);kind=event['kind']
            if kind in words or kind=='channel_optimized':
                text=('【AI降噪】为您优化了信道' if kind=='channel_optimized' else ((event.get('name','')+' ') if event.get('name') not in ('','null',None) and not kind.endswith('air_flowctrl') else '')+words[kind])
                rows.append({'eventTime':int(row['observed']*1000),'logContent':text,'eventTitle':text,
                             'eventTitleParam':[],'eventDetail':('将'+event['band']+'信号从'+str(event['before'])+'切换到'+str(event['after'])+'信道') if kind=='channel_optimized' else '','eventDetailParam':[],
                             'detailMark':None,'uuid':None,'optimizeStartTime':None})
        return {'optimizeCount':None,'optimizeDelay':None,'gameLogDetailList':rows,'source':'hub-firmware-rounds'}
