"""Original game environment protocol backed by current-device firmware reads."""
import copy
import json
import math
import secrets
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from .commands import UFCommand
from .devices import DeviceKey
from .daily import CHINA
from . import signal_tools
from .identifiers import mac_key
from .station_identity import choices, project_choice

LAST='hub.game_environment.last'
TAGS=('sta','wifi','ap','net','game')
NAMES={'sta':'游戏设备检测','wifi':'5G频段干扰检测','ap':'路由器负荷负载','net':'宽带检测','game':'游戏服务器检测'}

def number(value):
    try:
        value=float(str(value).rstrip('%'))
        return value if math.isfinite(value) else None
    except (ValueError,TypeError):return None

def result(tag,good,summary,score,basis):
    return {'tag':tag,'checkStatus':'success' if good else 'fail','checkSummary':summary,
            'checkInfo':summary,'checkInfoParam':'{}','nowScore':round(max(0,min(100,score)),1),
            'checkLevel':1 if good else 2,'checkDetail':json.dumps([] if tag=='sta' else {},ensure_ascii=False),
            'basis':basis,'source':'current-router-firmware'}

def details(row,value):
    # Original GameDiagnoseDetail JSON.parse's checkDetail and invokes the
    # original tag-specific renderer. Never pass a summary object as an array.
    row['checkDetail']=json.dumps(value,ensure_ascii=False)
    return row

GAME_DEVICE_TYPES={'phone','pad','tablet','pc','computer','laptop'}

def station_result(value,identities=None):
    rows=value.get('list')
    if not isinstance(rows,list):raise ValueError('固件终端列表无效')
    identities=identities or {}
    wireless=[r for r in rows if r.get('connectType')=='wireless' and
              str(identities.get(mac_key(r.get('mac')),r.get('devType') or r.get('type') or '')).lower() in GAME_DEVICE_TYPES]
    measured=[(r,number(r.get('rssi'))) for r in wireless]
    if any(rssi is None or not -120<=rssi<0 for _,rssi in measured):raise ValueError('固件无线终端信号值无效')
    weak=[r for r,rssi in measured if rssi<-70]
    ratio=round(len(weak)*100/len(wireless),1) if wireless else 0
    on24=sum(str(r.get('band','')).startswith('2.4') for r in wireless)
    summary=f'已检测{len(wireless)}台游戏设备，当前{on24}台处于2.4G，弱信号{len(weak)}台'
    if weak:summary+='；建议将弱信号终端靠近路由器'
    row=result('sta',not weak,summary,100-ratio,{'gameDeviceCount':len(wireless),'wirelessCount':len(wireless),'band24Count':on24,'weakCount':len(weak),
                   'weakPercent':ratio,'thresholdDbm':-70,'weakDevices':[{'name':r.get('name') or r.get('hostName') or r['mac'],'mac':r['mac'],'rssi':number(r['rssi'])} for r in weak]})
    return details(row,[{'mac':r['mac'],'name':r.get('name') or r.get('hostName') or r['mac'],
                         'hostName':r.get('hostName',''),'isGood':rssi>=-70,'judge':'弱信号' if rssi<-70 else '信号正常',
                         'staCheckNowData':{'staCheckNowStatus':{'rssiNow':rssi,'rssiCheckLevel':1 if rssi>=-70 else 2,
                                            'bandNow':r.get('band','--'),'bandCheckLevel':1}}} for r,rssi in measured])

def wifi_result(value):
    band=str(value.get('band','')).split(',')
    i=band.index('5G') if '5G' in band else -1
    def field(name):
        values=str(value.get(name,'')).split(',')
        return values[i] if 0<=i<len(values) else None
    util=number(field('chutil'));noise=number(field('channf'));channel=field('cur_channel')
    if i<0 or util is None or not 0<=util<=100 or noise is None or not -130<=noise<0:raise ValueError('固件5G信道测量无效')
    good=util<=50 and noise<=-85
    row=result('wifi',good,f'5G当前信道{channel}，信道利用率{util:g}%，底噪{noise:g}dBm'+('；建议优化信道' if not good else ''),
                  100-util,{'channel':channel,'utilizationPercent':util,'noiseDbm':noise})
    return details(row,{'wifiCheckNowData':{'nowItemList':[{'itemName':'5G','ch':channel,'chUse':util,'chNoise':noise,
                                                         'botherIndex':'小' if good else '高','isGood':good}]}})

def ap_result(value):
    cpu=number(value.get('cpuutil'));mem=number(value.get('memutil'))
    used=number(value.get('conntrack_count'));maximum=number(value.get('conntrack_max'))
    if cpu is None or not 0<=cpu<=100:raise ValueError('固件CPU占用值无效')
    sessions=used/maximum*100 if used is not None and maximum and maximum>0 else None
    good=cpu<85 and (mem is None or mem<95) and (sessions is None or sessions<80)
    text=f'CPU占用{cpu:g}%'
    if mem is not None:text+=f'，内存占用{mem:g}%'
    if sessions is not None:text+=f'，网络会话占用{sessions:.1f}%'
    row=result('ap',good,text,100-max(cpu,sessions or 0),{'cpuPercent':cpu,'memoryPercent':mem,'sessionPercent':sessions})
    items=[{'itemName':'CPU占用','otherData':f'{cpu:g}%','isGood':cpu<85}]
    if mem is not None:items.append({'itemName':'内存占用','otherData':f'{mem:g}%','isGood':mem<95})
    if sessions is not None:items.append({'itemName':'网络会话占用','otherData':f'{sessions:.1f}%','isGood':sessions<80})
    return details(row,{'apCheckNowData':{'nowItemList':items}})

def net_result(overview,ports):
    rows=ports.get('List')
    if not isinstance(rows,list):raise ValueError('固件端口列表无效')
    wan=[r for r in rows if r.get('name')=='WAN' and r.get('status')=='on']
    capacity=max((number(r.get('speed')) or 0 for r in wan),default=0)
    connected=overview.get('status') in ('connected','rcgame','healthy')
    down=number(overview.get('down_rate'));up=number(overview.get('up_rate'))
    if down is None or up is None:raise ValueError('固件WAN实时速率无效')
    # This is observed port load, never a purchased broadband-speed estimate.
    load=max(down,up)*8/(capacity*1_000_000)*100 if capacity>0 else None
    good=connected and capacity>=100 and (load is None or load<85)
    text=f'WAN连接'+('正常' if connected else '异常')+f'，协商速率{capacity:g}Mbps'
    if load is not None:text+=f'，当前端口占用{load:.2f}%'
    row=result('net',good,text,100-min(100,load or 0) if connected and capacity else 0,
                  {'wanConnected':connected,'portCapacityMbps':capacity,'portUtilizationPercent':round(min(100,load),2) if load is not None else None,
                   'downloadBytesPerSecond':down,'uploadBytesPerSecond':up,'capacitySource':'negotiated-WAN-port'})
    # Original overview supports the measured WAN negotiated speed. Leave
    # purchased bandwidth/recommendations absent rather than fabricate them.
    return details(row,{'wanSpeed':capacity,'isGood':good})

def game_result(value):
    rows=value.get('rows')
    if not isinstance(rows,list) or not rows:raise ValueError('固件未返回游戏服务器探测')
    reachable=[r for r in rows if r.get('status')=='success' and number(r.get('avg')) is not None and number(r.get('lossRate')) is not None]
    best=min(reachable,key=lambda r:float(r['avg']),default=None)
    good=bool(best and best['avg']<=80 and best['lossRate']<=5)
    if best:
        text=f"路由器到{best['host']}：ICMP平均延迟{best['avg']:g}ms，丢包率{best['lossRate']:g}%"
        score=max(0,100-best['avg']/2-best['lossRate'])
    else:text='游戏服务器ICMP探测未收到响应；服务器可能不响应ICMP，请结合实际游戏体验判断';score=0
    return details(result('game',good,text,score,{'probes':rows,'bestProbe':best,'measurement':'router-origin-ICMP'}),{'gameList':[]})

class GameEnvironment:
    def __init__(self,registry,store=None):
        self.registry=registry;self.store=store or registry.store
        self.pool=ThreadPoolExecutor(max_workers=2,thread_name_prefix='game-environment')
        self.lock=threading.RLock();self.jobs={};self.active={};self.slots=threading.BoundedSemaphore(2)
        with self.store.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS game_environment_history(tenant TEXT,project TEXT,sn TEXT,observed REAL,value TEXT)')
            db.execute('CREATE INDEX IF NOT EXISTS game_environment_scope ON game_environment_history(tenant,project,sn,observed)')

    def _read(self,key,module,namespace='dev_sta',data=None):
        with self.registry.device_operation(key) as driver:
            value=driver.execute(UFCommand(namespace,'get',module,data))
        if not isinstance(value,dict) or value.get('code') not in (None,0,'0'):raise ValueError('固件读取失败：'+module)
        return value

    def _new(self,key,job):
        return {'id':job,'sn':key.sn,'startTime':datetime.now(CHINA).strftime('%Y-%m-%d %H:%M:%S'),
                'allCheckStatus':None,'detailMap':{tag:{'tag':tag,'checkStatus':'todo','checkInfoParam':'{}'} for tag in TAGS},
                'source':'hub-current-router-firmware','measurementVersion':'hub-game-env-v1'}

    def start(self,key):
        with self.lock:
            if key in self.active:return self.active[key]
            if not self.slots.acquire(False):raise OverflowError('检测任务繁忙，请稍后重试')
            self.jobs={j:v for j,v in self.jobs.items() if time.monotonic()-v['created']<900}
            job=secrets.token_urlsafe(18);self.active[key]=job
            self.jobs[job]={'key':key,'created':time.monotonic(),'value':self._new(key,job),'optimizing':False}
        try:self.pool.submit(self._run,key,job,False)
        except Exception:
            with self.lock:self.active.pop(key,None);self.jobs.pop(job,None)
            self.slots.release();raise
        return job

    def _set(self,job,tag,value):
        with self.lock:
            entry=self.jobs[job]
            if entry['optimizing']:
                value['optimizeStatus']=value['checkStatus'];value['optimizeDetail']=value['checkSummary']
            entry['value']['detailMap'][tag]=value

    def _history(self,key,detail):
        now=time.time()
        with self.store.connect() as db:
            db.execute('DELETE FROM game_environment_history WHERE tenant=? AND project=? AND sn=? AND observed<?',(key.tenant,key.project,key.sn,now-14*86400))
            db.execute('INSERT INTO game_environment_history VALUES(?,?,?,?,?)',(key.tenant,key.project,key.sn,now,json.dumps(detail,ensure_ascii=False)))
            samples=[json.loads(r[0]) for r in db.execute('SELECT value FROM game_environment_history WHERE tenant=? AND project=? AND sn=? AND observed>=?',(key.tenant,key.project,key.sn,now-14*86400))]
        for tag,row in detail.items():
            values=[r[tag]['nowScore'] for r in samples if tag in r and 'nowScore' in r[tag] and r[tag].get('source')=='current-router-firmware']
            row['historyScore']=round(sum(values)/len(values),1) if values else row.get('nowScore',0)
            row['historySamples']=len(values)

    def _run(self,key,job,optimizing):
        released=False
        try:
            overview={}
            for tag in TAGS:
                try:
                    if tag=='sta':
                        saved=choices(self.store,key.tenant,key.project)
                        identities={}
                        for archived in self.store.stations(key.tenant,key.project):
                            mac=mac_key(archived.get('mac'))
                            if mac:identities[mac]=project_choice(dict(archived),saved.get(mac)).get('type','')
                        row=station_result(self._read(key,'user_list'),identities)
                    elif tag=='wifi':row=wifi_result(self._read(key,'radio_channel'))
                    elif tag=='ap':overview=self._read(key,'overview');row=ap_result(overview)
                    elif tag=='net':row=net_result(overview or self._read(key,'overview'),self._read(key,'port_status'))
                    else:row=game_result(self._read(key,'shinya_game',data={'action':'probe'}))
                except Exception as e:
                    row=result(tag,False,'检测未完成：'+str(e)[:150],0,{'error':type(e).__name__})
                    row['source']='firmware-read-failure'
                self._set(job,tag,row)
            with self.lock:
                value=self.jobs[job]['value'];detail=value['detailMap']
                self._history(key,detail)
                value['allCheckStatus']='fail' if any(r['checkStatus']=='fail' for r in detail.values()) else 'success'
                best=detail['game'].get('basis',{}).get('bestProbe')
                value['beforeDelay']=best['avg'] if best else None
                value['beforeLossRate']=best['lossRate'] if best else None
                if optimizing:
                    value['endTime']=datetime.now(CHINA).strftime('%Y-%m-%d %H:%M:%S')
                    previous=self.jobs[job]['before'];value['beforeDelay']=previous.get('beforeDelay');value['beforeLossRate']=previous.get('beforeLossRate')
                    value['afterDelay']=best['avg'] if best else None;value['afterLossRate']=best['lossRate'] if best else None
                    value['allOptimizeStatus']='fail' if value.get('optimizationError') else value['allCheckStatus']
                    for tag,row in detail.items():
                        row['optimizeScore']=row.get('nowScore',0);row['optimizeStatus']=row['checkStatus'];row['optimizeDetail']=row['checkSummary']
                        row['nowScore']=previous['detailMap'][tag].get('nowScore',0)
                else:
                    value['scanTime']=datetime.now(CHINA).strftime('%Y-%m-%d %H:%M:%S')
                    value.pop('endTime',None)
                    value['allOptimizeStatus']=None
                self.store.save(key.tenant,key.project,key.sn,LAST,value)
                self.active.pop(key,None);self.jobs[job]['optimizing']=False
                self.slots.release();released=True
        except Exception as e:
            with self.lock:
                value=self.jobs[job]['value']
                value['allOptimizeStatus' if optimizing else 'allCheckStatus']='fail'
                if optimizing:
                    value['endTime']=datetime.now(CHINA).strftime('%Y-%m-%d %H:%M:%S')
                else:
                    value['scanTime']=datetime.now(CHINA).strftime('%Y-%m-%d %H:%M:%S')
                    value.pop('endTime',None)
                value['taskError']=str(e)[:160]
                self.active.pop(key,None);self.jobs[job]['optimizing']=False
                self.slots.release();released=True
        finally:
            if not released:
                with self.lock:
                    self.active.pop(key,None)
                    if job in self.jobs:self.jobs[job]['optimizing']=False
                    self.slots.release()

    def last(self,key):
        cached=self.store.cached(key.tenant,key.project,key.sn,LAST)
        if not cached or not cached.get('value'): return None
        val=copy.deepcopy(cached['value'])
        if not val.get('allOptimizeStatus'):
            val.pop('endTime',None)
        return val

    def detail(self,key,job):
        with self.lock:
            row=self.jobs.get(str(job))
            if row:
                if row['key']!=key:raise PermissionError('检测结果不属于当前项目设备')
                return copy.deepcopy(row['value'])
        value=self.last(key)
        if value and str(value.get('id'))==str(job):return value
        raise LookupError('检测结果已过期，请重新检测')

    def current(self,key):
        value=self.last(key)
        if not value:return None
        d=value['detailMap'];bad=[NAMES[t] for t,r in d.items() if r['checkStatus']=='fail']
        return {'lastScanTime':value.get('scanTime') or value.get('endTime') or value.get('startTime'),'abnormalEvents':'、'.join(bad),
                'botherIndex':d['wifi'].get('basis',{}).get('utilizationPercent'),
                'weakSignalIndex':d['sta'].get('basis',{}).get('weakPercent'),
                'seizeIndex':d['net'].get('basis',{}).get('portUtilizationPercent'),
                'source':value['source'],'measurementVersion':value['measurementVersion']}

    def optimize(self,key,job):
        before=self.detail(key,job)
        if not before.get('allCheckStatus'):raise ValueError('请先完成环境检测')
        with self.lock:
            if key in self.active:raise ValueError('当前设备正在检测，请稍后重试')
            if not self.slots.acquire(False):raise OverflowError('检测任务繁忙，请稍后重试')
            self.active[key]=str(job)
            self.jobs[str(job)]={'key':key,'created':time.monotonic(),'before':before,
                                  'value':copy.deepcopy(before),'optimizing':True}
            self.jobs[str(job)]['value']['allOptimizeStatus']=None
            self.jobs[str(job)]['value']['endTime']=datetime.now(CHINA).strftime('%Y-%m-%d %H:%M:%S')
            self.jobs[str(job)]['value'].pop('optimizationError',None)
            for row in self.jobs[str(job)]['value']['detailMap'].values():row['optimizeStatus']='todo'
        def work():
            try:
                from . import game_acceleration
                self.jobs[str(job)]['value']['acceleration']=game_acceleration.change(self.registry,key,True)
                wifi=before['detailMap']['wifi']
                if wifi['checkStatus']=='fail' and wifi.get('source')=='current-router-firmware':
                    signal_tools.optimize(self.registry,key,'5G',trigger='game-environment')
            except Exception as e:
                with self.lock:self.jobs[str(job)]['value']['optimizationError']=str(e)[:160]
            self._run(key,str(job),True)
        try:self.pool.submit(work)
        except Exception:
            with self.lock:self.active.pop(key,None)
            self.slots.release();raise
        return str(job)

    def close(self):self.pool.shutdown(wait=True)
