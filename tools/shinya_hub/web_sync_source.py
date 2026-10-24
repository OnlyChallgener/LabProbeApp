"""Sync APP display names to native user_list_note, isolated per router."""
import hashlib,json,re,threading,time
from .commands import UFCommand
from .identifiers import mac_key

RESOURCE='hub.web.name-sync.v1'
NOTE=UFCommand('dev_config','get','user_list_note',None)
def clean_name(value):
 return ''.join(c for c in str(value or '') if re.fullmatch(r'[\u3400-\u9fffA-Za-z0-9_#-]',c))[:64]

class WebSync:
 def __init__(self,store,registry):
  self.store=store;self.registry=registry;self.locks={};self.lock=threading.Lock();self.stop=threading.Event();self.fingerprints={}
 def mutex(self,key):
  with self.lock:return self.locks.setdefault(key,threading.RLock())
 def status(self,key):
  value=self.store.cached(key.tenant,key.project,key.sn,RESOURCE)
  return 'on' if value and value['value'].get('enabled') else 'off'
 def desired(self,key):
  from .projection import station_snapshots
  project=next(p for p in self.store.projects(key.tenant) if p['projectId']==key.project)
  rows=station_snapshots(self.store,key.tenant,project)
  result=[]
  for row in rows:
   if row.get('sn')!=key.sn:continue
   name=clean_name(row.get('remark') or row.get('recommend') or row.get('hostname'))
   mac=mac_key(row['mac'])
   if name and re.fullmatch(r'(?:[0-9a-f]{2}:){5}[0-9a-f]{2}',mac):result.append({'mac':mac,'name':name})
  return sorted(result,key=lambda r:r['mac'])
 def apply(self,key,rows):
  if not rows:return 0
  with self.registry.device_operation(key) as driver:
   before=driver.execute(NOTE)
   if not isinstance(before,dict) or not isinstance(before.get('list'),list):raise ValueError('固件未返回设备名称配置')
   existing={mac_key(r['mac']):r.get('name','') for r in before['list']}
   changed=[r for r in rows if existing.get(r['mac'])!=r['name']]
   if not changed:return 0
   value=driver.execute(UFCommand('dev_config','update','user_list_note',{'obj':'eweb','list':changed}))
   if not isinstance(value,dict) or str(value.get('code'))!='0':raise ValueError('固件未确认设备名称同步')
   after=driver.execute(NOTE);names={mac_key(r['mac']):r.get('name','') for r in after.get('list',[])}
   if any(names.get(r['mac'])!=r['name'] for r in rows):raise ValueError('设备名称同步回读不一致')
   if any(names.get(mac)!=name for mac,name in existing.items() if mac not in {r['mac'] for r in changed}):raise ValueError('其他设备名称发生变化，请核对')
   self.store.save(key.tenant,key.project,key.sn,self.registry.resource(NOTE),after)
   return len(changed)
 def set(self,key,status):
  if status not in ('on','off'):raise ValueError('名称同步状态无效')
  with self.mutex(key):
   count=self.apply(key,self.desired(key)) if status=='on' else 0
   self.store.save(key.tenant,key.project,key.sn,RESOURCE,{'enabled':status=='on','synced':count,'error':''})
   self.fingerprints.pop(key,None)
   return status
 def start(self,keys):
  if self.registry is None:return
  def collect():
   while not self.stop.wait(15):
    for key in keys:
     with self.mutex(key):
      if self.status(key)!='on':continue
      try:
       rows=self.desired(key);fingerprint=hashlib.sha256(json.dumps(rows,ensure_ascii=False).encode()).hexdigest()
       if self.fingerprints.get(key)==fingerprint:continue
       count=self.apply(key,rows);self.fingerprints[key]=fingerprint
       self.store.save(key.tenant,key.project,key.sn,RESOURCE,{'enabled':True,'synced':count,'error':''})
      except Exception as error:
       self.store.save(key.tenant,key.project,key.sn,RESOURCE,{'enabled':True,'error':str(error)[:150]})
  threading.Thread(target=collect,name='shinya-web-name-sync',daemon=True).start()
