"""Current-router wireless operations with fresh readback and scoped scheduling."""
import copy
import json
import logging
import math
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from zoneinfo import ZoneInfo
from .commands import UFCommand
from .devices import DeviceRegistry
from . import signal_schedule as schedule
from .hub_scan import payload as survey_payload

LOG=logging.getLogger('shinya-signal')
CHINA=ZoneInfo('Asia/Shanghai')
META={'configId','configTime','currentTime','subConfigId','version'}

def command(namespace,module,data=None,action='get'):
    return UFCommand(namespace,action,module,data)

def flag(v):return v is True or str(v).lower() in {'1','true','on'}

def read(driver,namespace,module,data=None):
    value=driver.execute(command(namespace,module,data))
    if not isinstance(value,dict) or value.get('code') not in (None,0,'0'):
        raise RuntimeError('固件未返回有效的'+module+'数据')
    return value

def cache(registry,key,namespace,module,value,data=None):
    registry.store.save(key.tenant,key.project,key.sn,DeviceRegistry.resource(command(namespace,module,data)),value)

def effective_slot(driver,node):
    slot=node.get('slot')
    if slot:return slot
    name=node.get('tmngtName')
    if not name or name=='所有时段':return None
    value=read(driver,'ac_config','tmngt')
    for rows in value.values():
        if isinstance(rows,list):
            for row in rows:
                if str(row.get('tmngtName') or row.get('name'))==str(name):return row.get('slot') or row.get('time')
    raise RuntimeError('固件时间组不存在，请刷新后重试')

def wireless_state(registry,key,wlan=None,healthy=False):
    with registry.device_operation(key) as driver:
        value=read(driver,'ac_config','wireless');cache(registry,key,'ac_config','wireless',value)
        if healthy:
            node=value.get('healthy') or {};slot=effective_slot(driver,node) if flag(node.get('enable')) else None
            return {'sn':key.sn,'enable':flag(node.get('enable')),'timePolicies':schedule.policies(schedule.decode(slot)) if slot else []}
        rows=[]
        for s in value.get('ssidList',[]):
            slot=effective_slot(driver,s.get('tmValue') or {}) if s.get('tmMode')=='def' else None
            days=schedule.complement(schedule.decode(slot)) if slot else None
            item={'sn':key.sn,'wlanId':int(s['wlanId']),'ssid':s.get('ssidName',''),
                  'enable':bool(days and any(days.values())),
                  'timePolicies':schedule.policies(days) if days else []}
            if wlan is not None and item['wlanId']==int(wlan):return item
            if flag(s.get('enable')):rows.append(item)
        if wlan is not None:raise LookupError('未找到指定 Wi-Fi 网络')
        return {'rows':rows}

def _strip(value):return {k:v for k,v in value.items() if k not in META}

def change_wireless(registry,key,raw,healthy=False):
    if type(raw.get('enable'))!=bool:raise ValueError('定时开关格式无效')
    enabled=raw['enable'];policies=raw.get('timePolicies',[])
    days=schedule.expand(policies)
    if enabled and not any(days.values()):raise ValueError('请先添加定时')
    with registry.device_operation(key) as driver:
        current=read(driver,'ac_config','wireless');changed=copy.deepcopy(current)
        if healthy:
            target=changed.setdefault('healthy',{})
            target.update(enable='true' if enabled else 'false',mode='0',
                          tmngtName=('hub_eco_'+str(int(time.time()*1000))) if enabled else '',
                          slot=schedule.encode(days) if enabled else '')
        else:
            wlan=int(raw.get('wlanId',0))
            matches=[s for s in changed.get('ssidList',[]) if int(s['wlanId'])==wlan]
            if len(matches)!=1:raise LookupError('未找到指定 Wi-Fi 网络')
            target=matches[0]
            target['tmMode']='def'
            target['tmValue']={'tmngtName':('hub_wifi_'+str(wlan)+'_'+str(int(time.time()*1000))) if enabled else '所有时段',
                               'slot':schedule.encode(schedule.complement(days)) if enabled else ''}
        with registry._lock:registry._mutation_generation[key]=registry._mutation_generation.get(key,0)+1
        try:
            driver.execute(command('ac_config','wireless',_strip(changed),'set'))
            verified=read(driver,'ac_config','wireless')
            if healthy:
                node=verified.get('healthy') or {}
                if flag(node.get('enable'))!=enabled:raise RuntimeError('节能开关回读不一致')
                slot=effective_slot(driver,node) if enabled else None
                actual=schedule.decode(slot) if slot else {d:[] for d in range(1,8)}
                if enabled and actual!=days:raise RuntimeError('节能时段回读不一致')
            else:
                node=next(s for s in verified['ssidList'] if int(s['wlanId'])==wlan)
                slot=effective_slot(driver,node.get('tmValue') or {})
                actual=schedule.complement(schedule.decode(slot)) if slot else {d:[] for d in range(1,8)}
                if actual!=(days if enabled else {d:[] for d in range(1,8)}):raise RuntimeError('Wi-Fi 定时时段回读不一致')
            # Firmware may normalize metadata/time-group references. All actual
            # SSID, security and radio values must still retain the prior state.
            def other(v):
                v=copy.deepcopy(_strip(v))
                if healthy:v.pop('healthy',None)
                else:
                    for s in v.get('ssidList',[]):
                        if int(s['wlanId'])==wlan:s.pop('tmMode',None);s.pop('tmValue',None)
                return v
            if other(verified)!=other(current):raise RuntimeError('无线配置的其他字段发生变化，请核对设备')
            cache(registry,key,'ac_config','wireless',verified)
            return {'verified':True}
        finally:
            with registry._lock:registry._mutation_generation[key]=registry._mutation_generation.get(key,0)+1

def power(registry,key,data):
    rows=data.get('radioList') if isinstance(data,dict) else None
    if not isinstance(rows,list) or not rows:raise ValueError('射频功率配置无效')
    with registry.device_operation(key) as driver:
        current=read(driver,'dev_config','radio');changed=copy.deepcopy(current)
        requested={str(r.get('radioIndex')):str(r.get('txpower')) for r in rows}
        available={str(r.get('radioIndex')) for r in current.get('radioList',[])}
        if set(requested)!=available or any(v not in {'50','75','100','auto'} for v in requested.values()):raise ValueError('射频功率档位无效')
        for r in changed['radioList']:r['txpower']=requested[str(r['radioIndex'])]
        driver.execute(command('dev_config','radio',_strip(changed),'set'))
        actual=read(driver,'dev_config','radio')
        if {str(r['radioIndex']):str(r.get('txpower')) for r in actual.get('radioList',[])}!=requested:raise RuntimeError('射频功率回读不一致')
        cache(registry,key,'dev_config','radio',actual)
        return {'code':'0','verified':True}

def radio_state(registry,key):
    with registry.device_operation(key) as driver:
        state=read(driver,'dev_sta','radio_channel');cache(registry,key,'dev_sta','radio_channel',state)
    bands=str(state.get('band','')).split(',');channels=str(state.get('cur_channel','')).split(',')
    result={}
    for b,c in zip(bands,channels):
        if b in {'2.4G','5G'} and str(c).isdigit():result[b]=str(c)
    if not result:raise RuntimeError('未能读取实际信道')
    return result

def measurement(driver,band):
    # Both models expose this survey. BE50 does not implement chanIntfc; its
    # empty response must not be converted into a fabricated optimization.
    survey=read(driver,'dev_sta','shinya_exec',{'action':'survey','bands':['2.4G','5G']})
    bands=survey.get('bands')
    if not isinstance(bands,dict) or any(not isinstance(bands.get(b),list) for b in ('2.4G','5G')):
        raise RuntimeError('固件扫描未返回完整频段结果')
    result=survey_payload(survey,[],scan_time=int(time.time()*1000))
    names=['channel24Detail'] if band=='2.4G' else ['channel52Detail','channel58Detail']
    return [{'channel':r['channel'],'intfc':r['botherIndex']} for name in names for r in result[name]['channelInterfere']]

def optimize(registry,key,band,trigger='channel-optimize'):
    if band not in {'2.4G','5G'}:raise ValueError('频段无效')
    started=time.monotonic()
    with registry.device_operation(key) as driver:
        before=radio_state(registry,key)[band]
        measured=measurement(driver,band)
        legal=read(driver,'dev_sta','country_channel',{'qry_type':'channellist'})
        allowed={int(r['channel']) for r in legal.get('channel_2G' if band=='2.4G' else 'channel_5G',[]) if str(r.get('NopOccupancy','0'))=='0'}
        rows=[r for r in measured if r['channel'] in allowed]
        if not rows:raise RuntimeError('未取得可用信道，未进行切换')
        old=next((r['intfc'] for r in rows if str(r['channel'])==before),None)
        if old is None:raise RuntimeError('当前信道未包含在扫描结果中，未进行切换')
        # Prefer the current channel on equal measured neighbor scores. These
        # are the same HUB index units used by the original scan H5, not %.
        best=min(rows,key=lambda r:(r['intfc'],str(r['channel'])!=before,r['channel']))
        config=read(driver,'dev_config','radio');changed=copy.deepcopy(config)
        target=[r for r in changed.get('radioList',[]) if r.get('type')==band]
        if len(target)!=1:raise RuntimeError('射频配置与目标频段不一致')
        if str(best['channel'])!=before:
            target[0]['channel']=str(best['channel'])
            driver.execute(command('dev_config','radio',_strip(changed),'set'))
        for n in range(8):
            actual=radio_state(registry,key)
            if actual.get(band)==str(best['channel']):break
            time.sleep(1)
        else:raise RuntimeError('信道配置已提交，实际信道尚未切换完成，请刷新确认')
        cache(registry,key,'dev_config','radio',read(driver,'dev_config','radio'))
        result={'beforeChannel':before,'afterChannel':actual[band],
                'beforeBotherIndex':old,'afterBotherIndex':best['intfc'],
                'measurementUnit':'hub-neighbor-power-v1','measurementSource':'firmware-site-survey',
                'optimizedAt':int(time.time()*1000)}
        registry.store.save(key.tenant,key.project,key.sn,'hub.channel.optimize.'+band,result)
        from .game_reports import record_channel_action
        record_channel_action(registry.store,key,band,result,trigger)
        # The one-tap page waits on this call and nginx reads the upstream for
        # only 25 seconds, so the wall clock has to stay visible in the log.
        LOG.warning('channel optimize sn=%s band=%s %s->%s seconds=%.1f',key.sn,band,
                    result['beforeChannel'],result['afterChannel'],time.monotonic()-started)
        return result

def current(registry,key):
    state=radio_state(registry,key);result={}
    for band,suffix in (('2.4G','24'),('5G','5')):
        last=registry.store.cached(key.tenant,key.project,key.sn,'hub.channel.optimize.'+band)
        value=last['value'] if last else {}
        result['channel'+suffix]=state.get(band,'--')
        result['botherIndex'+suffix]=value.get('afterBotherIndex') if value.get('afterChannel')==state.get(band) else None
        result['lastOptimizeChannelTime'+suffix]=datetime.fromtimestamp(value['optimizedAt']/1000,CHINA).strftime('%Y-%m-%d %H:%M:%S') if value.get('optimizedAt') else None
    # The panel's 周边干扰情况 line is the last site survey — the same record the
    # 工具-信号-干扰扫描 page reads. Measured 2026-10-13: this page only ever calls
    # getLastChannelOptimizeResult, so the value has to travel with it.
    result['lastScanTime']=last_scan_time(registry,key)
    result['lastRefreshTime']=datetime.now(CHINA).strftime('%Y-%m-%d %H:%M:%S')
    return result

def cron(store,key,raw=None):
    cached=store.cached(key.tenant,key.project,key.sn,'hub.channel.cron_job')
    value=copy.deepcopy(cached['value']) if cached else {'sn':key.sn,'isSetting':False,'timerTimePolicy':[]}
    # Older adapters expanded the original deletion sentinel into all weekdays
    # while retaining the primitive time=0. It must never schedule midnight work.
    if raw is None and any(r.get('time') == 0 for r in value.get('timerTimePolicy',[]) if isinstance(r,dict)):
        value={'sn':key.sn,'isSetting':False,'timerTimePolicy':[],'executor':'hub','timezone':'Asia/Shanghai'}
        store.save(key.tenant,key.project,key.sn,'hub.channel.cron_job',value)
    if raw is None:
        # Original FID 32416 reads minutes directly from the policy. Migrate
        # the earlier adapter's nested time shape without changing its schedule.
        for r in value.get('timerTimePolicy',[]):
            if isinstance(r,dict) and 'minutes' not in r and isinstance(r.get('time'),dict):
                r['minutes']=r['time'].get('minutes')
    if raw is not None:
        rows=raw.get('timerTimePolicy',[])
        if not isinstance(rows,list) or len(rows)>1:raise ValueError('优化定时格式无效')
        rows=[r for r in rows if r is not None]
        if rows:
            r=rows[0]
            # Original FID 32463 sends precisely {weekDays:[],time:0} to
            # delete the timer. Filling empty weekdays re-enabled that switch.
            if r.get('weekDays')==[] and r.get('time')==0:
                value={'sn':key.sn,'isSetting':False,'timerTimePolicy':[],'executor':'hub','timezone':'Asia/Shanghai'}
                store.save(key.tenant,key.project,key.sn,'hub.channel.cron_job',value)
                return value
            days=r.get('weekDays')
            timer=r.get('time');minute=r.get('minutes',timer.get('minutes') if isinstance(timer,dict) else timer)
            if not isinstance(days,list) or any(type(d)!=int or not 1<=d<=7 for d in days) or type(minute)!=int or not 0<=minute<=1439:raise ValueError('优化定时时间无效')
            # Turning off retains the editing time, but cannot become enabled
            # just because the one-element policy array is still nonempty.
            setting=flag(r.get('isOpenTimer',r.get('isSetting',r.get('enable',True))))
            if setting and not days:raise ValueError('请选择优化日期')
            rows=[{'weekDays':sorted(set(days)),'minutes':minute,'isOpenTimer':setting}]
        else:setting=False
        value={'sn':key.sn,'isSetting':setting,'timerTimePolicy':rows,'executor':'hub','timezone':'Asia/Shanghai'}
        store.save(key.tenant,key.project,key.sn,'hub.channel.cron_job',value)
    return value

def last_scan_time(registry,key):
    """When the radio was last surveyed — the panel's 周边干扰情况 line.

    Side-by-side with the official app on 2026-10-13 proved this is the last
    site survey, not the last optimize: official showed 「昨天 19:05」 while both
    bands' optimize times were minutes apart from it.
    """
    from .signal_scan import RESULT
    row=registry.store.cached(key.tenant,key.project,key.sn,RESULT)
    scanned=int(((row or {}).get('value') or {}).get('scanTime') or 0)
    return datetime.fromtimestamp(scanned/1000,CHINA).strftime('%Y-%m-%d %H:%M:%S') if scanned else None

def current_channel_new(registry,key):
    """Channel panel aggregate: measured channels, the cloud-owned mode and the timer.

    Isolated taps showed the official cloud never pushes the mode to the router,
    so it is reported from the HUB's own preference store and labelled as such.
    """
    from .game_acceleration import preferences
    state=current(registry,key)
    job=cron(registry.store,key)
    return {'optimizeChannelMode': preferences(registry.store,key)['optimizeChannelMode'],
            'lastScanTime': state.get('lastScanTime'),
            'isOpenTimer': bool(job.get('isSetting')),
            'timePolicies': copy.deepcopy(job.get('timerTimePolicy',[])),
            'channel24': state.get('channel24'), 'channel5': state.get('channel5'),
            'source': 'hub-measured-channel-and-cloud-preference'}

class ChannelTimer:
    def __init__(self,registry):
        self.registry=registry;self.pool=ThreadPoolExecutor(max_workers=2,thread_name_prefix='channel-timer');self.pending=set();self.lock=threading.Lock()
    def tick(self,keys,now=None):
        now=now or datetime.now(CHINA)
        for key in keys:
            value=cron(self.registry.store,key)
            for row in value.get('timerTimePolicy',[]) if value.get('isSetting') else []:
                if not isinstance(row,dict):continue
                if now.isoweekday() not in row.get('weekDays',[]) or now.hour*60+now.minute!=row.get('minutes'):continue
                marker=now.strftime('%Y-%m-%d %H:%M');store=self.registry.store
                with self.lock:
                    last=store.cached(key.tenant,key.project,key.sn,'hub.channel.cron_run')
                    if key in self.pending or (last and last['value'].get('minute')==marker):continue
                    self.pending.add(key);store.save(key.tenant,key.project,key.sn,'hub.channel.cron_run',{'minute':marker,'status':'running'})
                def job(key=key,marker=marker):
                    try:
                        for band in radio_state(self.registry,key):optimize(self.registry,key,band)
                        result={'minute':marker,'status':'complete'}
                    except Exception as e:
                        LOG.warning('Scheduled channel optimization failed for %s: %s',key.sn,type(e).__name__)
                        result={'minute':marker,'status':'failed','message':str(e)[:160]}
                    finally:
                        self.registry.store.save(key.tenant,key.project,key.sn,'hub.channel.cron_run',result)
                        with self.lock:self.pending.discard(key)
                self.pool.submit(job)
    def close(self):self.pool.shutdown(wait=True)
