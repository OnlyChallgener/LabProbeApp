"""Optional official account shared by its matched tenant projects."""
import copy,threading
from datetime import datetime
from zoneinfo import ZoneInfo
from pathlib import Path
from .vault import ConfigVault
from .official_channels import OfficialChannelError

class OfficialAccount:
 def __init__(self,channels,store,directory):
  self.channels=channels;self.store=store;self.vault=ConfigVault(Path(directory));self.lock=threading.RLock()
  self.saved=self.vault.load() if self.vault.value_path.exists() else {'bindings':[]}
  for row in self.saved.get('bindings',[]):
   if self.store.owned_device(row['tenant'],row['project'],row['sn']):self.install(row)
 def install(self,row):
  if self.channels is None:return
  identity=(row['tenant'],row['project'],row['sn'])
  self.channels.bindings[identity]=copy.deepcopy(row)
  self.channels.locks.setdefault(identity,threading.Lock())
  self.channels.account_locks.setdefault(self.channels.account_key(row),threading.Lock())
  self.channels.tunnels.pop(identity,None);self.channels.web_links.pop(identity,None)
 def status(self,key):
  row=self.channels.bindings.get((key.tenant,key.project,key.sn)) if self.channels else None
  if not row and self.channels:
   row=next((v for v in self.channels.bindings.values() if v.get('tenant')==key.tenant and v.get('token') and v.get('account')),None)
  account=str((row or {}).get('account') or '')
  masked=account[:3]+'****'+account[-4:] if len(account)>=8 else (account[:1]+'***' if account else '')
  projects={v.get('project') for v in self.channels.bindings.values() if v.get('tenant')==key.tenant and v.get('account')==account and v.get('token')} if self.channels and account else set()
  return {'bound':bool(row and row.get('token')),'account':masked,'sn':key.sn,'projectCount':len(projects)}
 def bind(self,key,account,password):
  if not isinstance(account,str) or not isinstance(password,str) or not 1<=len(account.strip())<=128 or not 1<=len(password)<=256:
   raise ValueError('请填写官方账号和密码')
  if not self.store.owned_device(key.tenant,key.project,key.sn):raise PermissionError('路由器不属于当前项目')
  if self.channels is None:raise ValueError('官方功能尚未启用')
  with self.lock:
   s=self.channels.factory();s.trust_env=False
   try:
    def call(path,body,token=''):
     headers={'type':'shinyaApp','Accept':'application/json'}
     if token:headers['Authorization']='Bearer '+token
     try:
      r=s.post(self.channels.BASE+path,json=body,headers=headers,timeout=(4,18),allow_redirects=False)
      if r.status_code!=200 or len(r.content)>2*1024*1024:raise ValueError('官方服务暂不可用')
      value=r.json()
     except Exception as error:raise ValueError('官方服务暂时连接失败') from error
     if str(value.get('code'))!='0':raise ValueError('官方账号验证未通过，请检查账号和密码')
     return value.get('data')
    login=call('/api/v1/base/homeUser/accountPasswordLogin',{'account':account.strip(),'password':password})
    token=(login or {}).get('token')
    if not isinstance(token,str) or not token:raise ValueError('官方账号未返回有效授权')
    data=call('/homewlan/apMonitor/getProjectList2',{},token)
    projects=data.get('dataList',[]) if isinstance(data,dict) else data if isinstance(data,list) else []
    matched_rows=[]
    for local in self.store.projects(key.tenant):
     for device in local.get('apList',[]):
      sn=device.get('sn');project=local['projectId']
      if not self.store.owned_device(key.tenant,project,sn):continue
      matches=[v for v in projects if v.get('mainApSn')==sn or any(a.get('sn')==sn for a in v.get('apList',[]))]
      if len(matches)!=1:continue
      old=self.channels.bindings.get((key.tenant,project,sn)) or {}
      row={**copy.deepcopy(old),'tenant':key.tenant,'project':project,'sn':sn,'officialProjectId':matches[0]['projectId'],'account':account.strip(),'password':password,'token':token}
      if old.get('account')!=account.strip():
       for field in ('maccEnabled','maccCookies','maccAccount'):row.pop(field,None)
      matched_rows.append(row)
    if not matched_rows:raise ValueError('官方账号中没有匹配的 HUB 路由器项目')
    identities={(v['tenant'],v['project'],v['sn']) for v in matched_rows}
    rows=[v for v in self.saved.get('bindings',[]) if (v['tenant'],v['project'],v['sn']) not in identities]+matched_rows
    self.vault.save({'bindings':rows});self.saved={'bindings':rows}
    for row in matched_rows:self.install(row)
    return {**self.status(key),'verified':True}
   finally:s.close()
