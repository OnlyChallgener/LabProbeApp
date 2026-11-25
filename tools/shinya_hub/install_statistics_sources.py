"""Apply audited backend changes to the independent HUB project."""
from pathlib import Path
import shutil

root=Path('D:/Github/ShinyaHub')
stage=Path(__file__).parent/'staged_statistics'
for source in stage.glob('*.py'):
    target=(root/'tests'/source.name if source.name.startswith('test_') else root/'shinya_hub'/source.name)
    shutil.copyfile(source,target)

def replace(path, old, new):
    target=root/path
    text=target.read_text(encoding='utf-8')
    if new in text:return
    assert text.count(old)==1,(str(path),text.count(old))
    target.write_text(text.replace(old,new),encoding='utf-8')

replace('shinya_hub/store.py','            initialize_daily(db)',
    '            initialize_daily(db)\n            from .statistics import initialize as initialize_statistics\n            initialize_statistics(db)')
replace('shinya_hub/daily.py','    # Called only for validated native user_list results, never imported caches.',
    '    from .statistics import record_station_interval, record_drop\n    # Called only for validated native user_list results, never imported caches.')
replace('shinya_hub/daily.py',"        db.execute('BEGIN IMMEDIATE')","        if not db.in_transaction:db.execute('BEGIN IMMEDIATE')")
replace('shinya_hub/daily.py',"                    seconds if valid and sum(delta.values()) > 0 else 0, start, end))",
    "                    seconds if valid and sum(delta.values()) > 0 else 0, start, end))\n                record_station_interval(db,tenant,project,mac,day,before,row,seconds)")
replace('shinya_hub/daily.py',"            if old['sn'] == sn and mac not in current and old['observed'] < observed:\n                db.execute",
    "            if old['sn'] == sn and mac not in current and old['observed'] < observed:\n                old_row=json.loads(old['value'])\n                if observed-old['observed']<=MAX_GAP and old_row.get('band') in ('2.4G','5G','6G'):\n                    record_drop(db,tenant,project,mac,observed)\n                db.execute")
replace('shinya_hub/devices.py','from .daily import record as record_daily',
    'from .daily import record as record_daily\nfrom .statistics import record_resource')
replace('shinya_hub/devices.py','            if not changed_during_read and is_stations:',
    '            if not changed_during_read:\n                record_resource(self.store,key,command,value,collected_at)\n            if not changed_during_read and is_stations:')
replace('shinya_hub/devices.py','        if cached is None or time.time() - cached["observedAt"] > ttl:',
    '''        # Fresh native push snapshots suppress redundant eWeb polling.
        # Every module still falls back individually after 90 seconds.
        if cached and command.namespace == "dev_sta" and command.module in {
                "sysinfo", "user_list", "flow", "overview", "radioInfo", "ap_list"}:
            if self.store.push_fresh(key.tenant,key.project,key.sn):
                ttl=max(ttl,90)
        if cached is None or time.time() - cached["observedAt"] > ttl:''')
replace('shinya_hub/store.py','    def cached(self, tenant: str, project: str, sn: str, resource: str):',
    '''    def push_fresh(self, tenant, project, sn, now=None):
        now=time.time() if now is None else now
        with self.connect() as db:
            # Old databases can be read before the push worker starts.
            if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='push_transport'").fetchone():
                return False
            row=db.execute("SELECT observed FROM push_transport WHERE tenant=? AND project=? AND sn=?",
                           (tenant,project,sn)).fetchone()
            return bool(row and 0 <= now-row['observed'] < 90)

    def cached(self, tenant: str, project: str, sn: str, resource: str):''')
replace('shinya_hub/store.py','import sqlite3','import sqlite3\nimport threading')
replace('shinya_hub/store.py','        self.path = str(path)','        self.path = str(path)\n        self._transaction = threading.local()')
replace('shinya_hub/store.py','        self._transaction = threading.local()',
    '        self._transaction = threading.local()\n        self._write_lock = threading.RLock()')
replace('shinya_hub/store.py','    def connect(self):\n        db = sqlite3.connect',
    '''    def connect(self):
        active=getattr(self._transaction,"db",None)
        if active is not None:
            yield active
            return
        db = sqlite3.connect''')
replace('shinya_hub/store.py','    def add_project(self, tenant: str, project: str, name: str):',
    '''    @contextmanager
    def atomic(self):
        # One native frame is one short write transaction and one disk commit.
        if getattr(self._transaction,"db",None) is not None:
            yield
            return
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self._transaction.db=db
            try:yield
            finally:del self._transaction.db

    def add_project(self, tenant: str, project: str, name: str):''')
replace('shinya_hub/store.py','        with self.connect() as db:\n            db.execute("BEGIN IMMEDIATE")',
    '        with self._write_lock, self.connect() as db:\n            db.execute("BEGIN IMMEDIATE")')
replace('shinya_hub/runtime.py','from .telemetry import USER_LIST','from .telemetry import USER_LIST, WAN_FLOW')
replace('shinya_hub/runtime.py','                registry.cached_read(key, USER_LIST, ttl=30)',
    '                registry.cached_read(key, USER_LIST, ttl=30)\n                registry.cached_read(key, WAN_FLOW, ttl=30)\n                registry.cached_read(key, UFCommand("dev_sta","get","overview",None), ttl=30)\n                registry.cached_read(key, UFCommand("dev_sta","get","radioInfo",None), ttl=60)')
replace('shinya_hub/projection.py','from .daily import summary as daily_summary',
    'from .daily import summary as daily_summary\nfrom .statistics import station_metrics')
replace('shinya_hub/projection.py',"    daily = daily_summary(store, tenant, project['projectId'])",
    "    daily = daily_summary(store, tenant, project['projectId'])\n    quality = station_metrics(store,tenant,project['projectId'])")
replace('shinya_hub/projection.py',"        if sn in acl:\n            row['isBlack']",
    "        stats=quality.get(mac_key(raw['mac']),{})\n        row['rxRateAvg']=stats.get('rxRateAvg')\n        row['txRateAvg']=stats.get('txRateAvg') if stats.get('txRateAvg') is not None else '--'\n        row['iotShakeCnt']=stats.get('dropCount')\n        row['rssiAvg']=round(stats['rssi_sum']/stats['rssi_seconds']) if stats.get('rssi_seconds') else None\n        if stats.get('lastDropAt'):\n            from .daily import CHINA\n            row['latestDevTime']=datetime.fromtimestamp(stats['lastDropAt'],CHINA).strftime('%Y-%m-%d %H:%M:%S')\n        else:row['latestDevTime']=None\n        if sn in acl:\n            row['isBlack']")
replace('shinya_hub/api.py','from .identifiers import mac_key',
    'from .identifiers import mac_key\nfrom .statistics import speed as speed_statistics, stability as stability_statistics')
replace('shinya_hub/api.py',"            if resource == 'experienceVisual/getApOverview':\n                key=selected_device(principal,raw)",
    "            if resource in {'experienceVisual/getApSpeedRate','experienceVisual/getNetStabilizeIndex','experienceVisual/getApOverview'}:\n                project=selected_project(principal,raw)\n                main=next((d for d in project['apList'] if d.get('isMain')),project['apList'][0] if project['apList'] else None)\n                if not main:raise LookupError('项目尚未绑定路由器')\n                key=selected_device(principal,{**raw,'apSn':raw.get('apSn') or main['sn']})\n                if resource.endswith('getApSpeedRate'):\n                    registry.cached_read(key,WAN_FLOW,ttl=30)\n                    return reply(speed_statistics(store,key.tenant,key.project,key.sn))\n                if resource.endswith('getNetStabilizeIndex'):\n                    registry.cached_read(key,UFCommand('dev_sta','get','overview',None),ttl=30)\n                    registry.cached_read(key,UFCommand('dev_sta','get','radioInfo',None),ttl=60)\n                    return reply(stability_statistics(store,key.tenant,key.project,key.sn))")
replace('shinya_hub/api.py',"                native=cached['value'];uptime=number(native.get('runtime'))\n                data={}",
    "                native=cached['value'];uptime=number(native.get('runtime'))\n                data={}\n                temp=number(native.get('temp'))\n                if temp is not None:data['predictApTemp']=round(temp,1)")
replace('shinya_hub/api.py',"return reply(speed_statistics(store,key.tenant,key.project,key.sn))",
    "return reply(speed_statistics(store,key.tenant,key.project,key.sn,\n                                                  logarithmic=request.headers.get('X-Shinya-Chart') in {'log-v1','smooth-v2'}))")
target=root/'scripts/update_nas.py'
text=target.read_text(encoding='utf-8')
shutil.copyfile(Path(__file__).parent/'remote_transport.py',root/'scripts/remote_transport.py')
if 'from remote_transport import connect_nas' not in text:
    text=text.replace('from eweb_hub_remote import connect, run, write',
                      'from eweb_hub_remote import connect, run, write\nfrom remote_transport import connect_nas')
text=text.replace("client=connect(os.environ['SHINYA_NAS_HOST'],int(os.environ['SHINYA_NAS_PORT']),user,password)",
                  'client=connect_nas()')
for previous in ('oct09-daily-v13','oct09-statistics-v14'):
    text=text.replace("IMAGE='shinya-hub:"+previous+"'","IMAGE='shinya-hub:oct09-mqtt-v15'")
target.write_text(text,encoding='utf-8')
replace('shinya_hub/store.py','                value=excluded.value,observed=excluded.observed""",',
    '                value=excluded.value,observed=excluded.observed WHERE excluded.observed>=snapshots.observed""",')
print('Independent HUB statistics sources installed.')
