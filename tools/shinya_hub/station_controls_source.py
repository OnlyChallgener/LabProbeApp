"""Native station controls and durable events, scoped to one tenant/project/router."""
import copy
import ipaddress
import re
import time
import statistics
from datetime import datetime, timedelta
from .blacklist import station_mac
from .commands import UFCommand
from .daily import CHINA
from .identifiers import mac_key, app_mac
from .devices import DeviceRegistry

APSELECT=UFCommand('ac_config','get','apselect',None)
EVENTS=UFCommand('dev_sta','get','shinya_station',{'action':'history'})

def read_only5g(registry,key,fresh=False):
 with registry.device_operation(key) as driver:
  cached=registry.store.cached(key.tenant,key.project,key.sn,registry.resource(APSELECT))
  if not fresh and cached and time.time()-cached['observedAt']<5:value=cached['value']
  else:
   value=driver.execute(APSELECT)
   if not isinstance(value,dict) or not isinstance(value.get('apselect_list'),list):
    raise ValueError('路由器未返回有效的频段选择规则')
   registry.store.save(key.tenant,key.project,key.sn,registry.resource(APSELECT),value)
  return only5g_rows(value)

def only5g_rows(value):
 return [{'staMac':app_mac(row['mac']),'mac':app_mac(row['mac']),'isBlack':1,'isTemporarily':False}
         for row in value.get('apselect_list',[]) if row.get('sel_band')=='5G' and row.get('mac')]

def change_only5g(registry,key,mac,enabled):
 mac=station_mac(mac)
 with registry.device_operation(key) as driver:
  before=driver.execute(APSELECT)
  if not isinstance(before,dict) or not isinstance(before.get('apselect_list'),list):
   raise ValueError('路由器未返回有效的频段选择规则')
  expected=copy.deepcopy(before['apselect_list'])
  matches=[row for row in expected if mac_key(row.get('mac'))==mac]
  if len(matches)>1:raise ValueError('设备频段规则重复，请先核对固件配置')
  if enabled:
   if matches:matches[0]['sel_band']='5G'
   else:expected.append({'mac':mac,'sel_ap':'auto','sel_band':'5G'})
  elif matches:
   # Preserve a separately configured router lock when releasing only the band.
   if matches[0].get('sel_ap') not in (None,'','auto'):
    matches[0]['sel_band']='auto'
   else:expected.remove(matches[0])
  with registry._lock:registry._mutation_generation[key]=registry._mutation_generation.get(key,0)+1
  try:
   if expected!=before['apselect_list']:
    result=driver.execute(UFCommand('ac_config','set','apselect',{'apselect_list':expected}))
    if not isinstance(result,dict) or result.get('code') not in (None,0,'0') or result.get('rcode') not in (None,0,'0','00000000'):
     raise ValueError('路由器未确认频段选择配置')
   after=driver.execute(APSELECT)
   def normalized(rows):return sorted(rows,key=lambda row:mac_key(row.get('mac')))
   if not isinstance(after,dict) or normalized(after.get('apselect_list',[]))!=normalized(expected):
    raise ValueError('频段选择规则尚未通过固件回读确认')
   registry.store.save(key.tenant,key.project,key.sn,registry.resource(APSELECT),after)
   registry.store.save(key.tenant,key.project,key.sn,'hub.esport.only5g',only5g_rows(after))
   return {'staMac':app_mac(mac),'isBlack':int(enabled),'verified':True}
  finally:
   with registry._lock:registry._mutation_generation[key]=registry._mutation_generation.get(key,0)+1

def change_rate(registry,key,command,station_ips):
 if command.action not in ('add','update','del'):raise ValueError('仅支持单个终端限速的增删和更新')
 rows=command.data.get('list') if isinstance(command.data,dict) else None
 if not isinstance(rows,list) or len(rows)!=1 or not isinstance(rows[0],dict):
  raise ValueError('一次只能修改当前项目的一台终端限速')
 row=copy.deepcopy(rows[0]);parts=str(row.get('ipRange','')).split('-')
 if len(parts)!=2 or parts[0]!=parts[1] or parts[0] not in station_ips:
  raise PermissionError('限速目标不属于当前项目终端')
 ipaddress.IPv4Address(parts[0])
 if row.get('mode')!='per_ip' or row.get('intf')!='br-wan':raise ValueError('终端限速规则类型无效')
 if command.action!='del':
  for name in ('upRate','downRate'):
   v=str(row.get(name,''))
   if not re.fullmatch(r'\d+',v) or not 1<=int(v)<=10000000:raise ValueError('限速数值无效')
 with registry.device_operation(key) as driver:
  getter=UFCommand('dev_config','get','flowctrl_udp',None)
  before=driver.execute(getter)
  if not isinstance(before,dict) or not isinstance(before.get('list'),list):raise ValueError('固件限速规则格式无效')
  current=copy.deepcopy(before['list'])
  matches=[v for v in current if v.get('ipRange')==row['ipRange'] and v.get('mode')=='per_ip']
  if len(matches)>1:raise ValueError('该终端存在多条限速规则，请先核对')
  if command.action in ('update','del') and not matches:raise ValueError('原限速规则已变化，请重新打开设置')
  if command.action=='add' and matches:raise ValueError('该终端已有规则，请重新打开设置')
  if matches and str(row.get('pri'))!=str(matches[0].get('pri')):raise ValueError('限速规则标识已变化，请重新打开设置')
  with registry._lock:registry._mutation_generation[key]=registry._mutation_generation.get(key,0)+1
  try:
   result=driver.execute(command)
   if not isinstance(result,dict) or result.get('code') not in (None,0,'0') or result.get('rcode') not in (None,0,'0','00000000'):
    raise ValueError('路由器未确认限速配置')
   after=driver.execute(getter)
   values=after.get('list',[]) if isinstance(after,dict) else []
   other=[v for v in current if v not in matches]
   for v in other:
    if v not in values:raise ValueError('其他终端限速规则发生变化，请核对')
   target=[v for v in values if v.get('ipRange')==row['ipRange'] and v.get('mode')=='per_ip']
   if command.action=='del':verified=not target and len(values)==len(other)
   else:verified=len(target)==1 and len(values)==len(other)+1 and all(str(target[0].get(n))==str(row.get(n)) for n in ('upRate','downRate','enable'))
   if not verified:raise ValueError('限速规则尚未通过固件回读确认')
   registry.store.save(key.tenant,key.project,key.sn,registry.resource(getter),after)
   return result
  finally:
   with registry._lock:registry._mutation_generation[key]=registry._mutation_generation.get(key,0)+1

def check_rdp(registry,key,ip):
 ip=str(ipaddress.IPv4Address(ip))
 with registry.device_operation(key) as driver:
  value=driver.execute(UFCommand('dev_sta','get','shinya_station',{'action':'rdp','ip':ip}))
 if not isinstance(value,dict) or value.get('ip')!=ip or type(value.get('open')) is not bool:
  raise ValueError('路由器未返回有效的远程桌面检测结果')
 return value['open']

def record_events(store,key,value):
 if not isinstance(value,dict) or not isinstance(value.get('events'),list):raise ValueError('固件在网记录格式无效')
 now=time.time()
 with store.connect() as db:
  db.execute('''CREATE TABLE IF NOT EXISTS station_events(tenant TEXT,project TEXT,sn TEXT,mac TEXT,
   event_key TEXT,kind INTEGER,happened INTEGER,source TEXT,PRIMARY KEY(tenant,project,sn,event_key))''')
  for v in value['events']:
   mac=station_mac(v.get('mac'));at=v.get('eventTime');kind=v.get('eventType');event_key=v.get('eventKey')
   if type(at) not in (int,float) or not now-4*86400<=at<=now+5 or kind not in (1,2) or not isinstance(event_key,str) or len(event_key)>180:continue
   db.execute('INSERT OR IGNORE INTO station_events VALUES(?,?,?,?,?,?,?,?)',
              (key.tenant,key.project,key.sn,mac,event_key,kind,int(at),str(v.get('source',''))[:40]))
  db.execute('DELETE FROM station_events WHERE tenant=? AND project=? AND sn=? AND happened<?',
             (key.tenant,key.project,key.sn,now-4*86400))

def normalize_events(values,short_gap=5):
 """Keep chronological associations, merging confirmed immediate reconnects.

 Hostapd's monotonic event key retains subsecond order even for old records
 whose wall-clock timestamp was truncated. A sleep flag alone is never an
 offline event; only actual disconnect/reconnect pairs may be combined.
 """
 origins={};monotonic={}
 for v in values:
  if v['source']!='hostapd':continue
  try:
   boot,elapsed=v['event_key'].rsplit(':',2)[-2:];elapsed=float(elapsed)
   monotonic[v['event_key']]=(boot,elapsed);origins.setdefault(boot,[]).append(v['happened']-elapsed)
  except (ValueError,KeyError):pass
 origins={boot:statistics.median(times) for boot,times in origins.items()}
 def order(v):
  if v['event_key'] in monotonic:
   boot,elapsed=monotonic[v['event_key']];return (origins[boot]+elapsed,0)
  return (v['happened'],1)
 host_times=[order(v)[0] for v in values if v['source']=='hostapd']
 # Native accounting logs round/restate sessions separately. Use association
 # logs inside their observed span instead of interleaving contradictory rows.
 if host_times:
  lo,hi=min(host_times)-5,max(host_times)+5
  values=[v for v in values if v['source']=='hostapd' or not lo<=v['happened']<=hi]
 timeline=[]
 for v in sorted(values,key=order):
  at=order(v)[0];kind=v['kind']
  if timeline and timeline[-1]['eventType']==kind:continue
  timeline.append({'eventType':kind,'eventTime':round(at*1000)})
 rows=[]
 for event in timeline:
  if event['eventType']==1 and rows and rows[-1]['eventType']==2 and 0<=event['eventTime']-rows[-1]['eventTime']<=short_gap*1000:
   # A real pair of transitions within 5 seconds is one brief reconnect.
   # Retain a starting association when it precedes the returned log window.
   if len(rows)>1 and rows[-2]['eventType']==1:rows.pop();continue
   rows.pop()
  rows.append(event)
 return rows

def event_history(registry,key,mac,day):
 mac=station_mac(mac);today=datetime.now(CHINA).replace(hour=0,minute=0,second=0,microsecond=0)
 start=datetime.strptime(day or today.strftime('%Y-%m-%d'),'%Y-%m-%d').replace(tzinfo=CHINA)
 start=max(start,today-timedelta(days=2));end=today+timedelta(days=1)
 with registry.device_operation(key) as driver:
  record_events(registry.store,key,driver.execute(EVENTS))
 with registry.store.connect() as db:
  values=db.execute('''SELECT kind,happened,source,event_key FROM station_events WHERE tenant=? AND project=? AND sn=? AND mac=?
      AND happened>=? AND happened<? ORDER BY happened,kind''',
      (key.tenant,key.project,key.sn,mac,start.timestamp(),end.timestamp())).fetchall()
 rows=normalize_events(values)
 rows.reverse()
 return {'rows':rows,'list':rows}
