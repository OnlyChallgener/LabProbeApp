"""Install owned official tunnel adapters and original child selection reads."""
from pathlib import Path

ROOT=Path('D:/Github/ShinyaHub')
MODULE=r'''"""Optional official channels, bound to a HUB tenant/project/router.

The APP's HUB token never crosses this boundary. Credentials are read only
from the encrypted configuration; URLs and official project IDs are not
accepted from the APP. This adapter does not import the old HUB process.
"""
import threading
from datetime import datetime
from zoneinfo import ZoneInfo
import requests

PATHS={
 'homeAp/getTunnelUrl':'/homewlan/homeAp/getTunnelUrl',
 'remoteAccess/checkStaRdp':'/homewlan/remoteAccess/checkStaRdp',
 'remoteAccess/getRdpTunnel':'/homewlan/remoteAccess/getRdpTunnel',
}

class OfficialChannelError(Exception):
 def __init__(self,message,code=503):
  super().__init__(message);self.code=code

class OfficialChannels:
 BASE='https://shinyaapp.ruijie.com.cn'
 def __init__(self,bindings,session_factory=requests.Session):
  self.bindings={};self.locks={};self.factory=session_factory
  self.slots=threading.BoundedSemaphore(3)
  for value in bindings:
   row=dict(value);identity=tuple(str(row[k]) for k in ('tenant','project','sn'))
   if identity in self.bindings or not row.get('officialProjectId'):
    raise ValueError('Invalid official channel binding')
   self.bindings[identity]=row;self.locks[identity]=threading.Lock()

 def request(self,key,resource,station_ip=None):
  identity=(key.tenant,key.project,key.sn)
  if resource not in PATHS:raise ValueError('Unsupported official channel')
  if identity not in self.bindings:
   raise OfficialChannelError('当前项目尚未绑定官方通道账号',409)
  if not self.slots.acquire(blocking=False):
   raise OfficialChannelError('通道请求较多，请稍后重试',429)
  locked=False;session=None
  try:
   locked=self.locks[identity].acquire(blocking=False)
   if not locked:raise OfficialChannelError('当前路由器正在建立通道',429)
   row=self.bindings[identity];session=self.factory();session.trust_env=False
   def call(path,body,login=False):
    headers={'Accept':'application/json','Content-Type':'application/json','type':'shinyaApp'}
    if not login:
     headers.update(Authorization='Bearer '+str(row.get('token','')),
                    projectId=str(row['officialProjectId']))
    response=session.post(self.BASE+path,json=body,headers=headers,
                          timeout=(4,12),allow_redirects=False)
    if response.status_code in (401,403):
     raise OfficialChannelError('官方账号授权已失效',401)
    if response.status_code!=200 or len(response.content)>1024*1024:
     raise OfficialChannelError('官方通道暂不可用')
    value=response.json()
    if not isinstance(value,dict) or 'code' not in value:
     raise OfficialChannelError('官方通道响应格式异常')
    if str(value['code']) in ('401','403'):
     raise OfficialChannelError('官方账号授权已失效',401)
    if str(value['code'])!='0':
     raise OfficialChannelError('官方通道未接受此次请求',409)
    return value
   def verify_router():
    value=call('/homewlan/homeAp/getDeviceInfoList2',{
     'projectId':row['officialProjectId'],
     'day':datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d')})
    data=value.get('data') or {}
    if not isinstance(data,dict) or not any(isinstance(d,dict) and str(d.get('sn'))==key.sn
                                          for d in data.get('apList',[])):
     raise OfficialChannelError('官方账号与当前路由器不匹配',403)
   try:verify_router()
   except OfficialChannelError as error:
    if error.code!=401 or not row.get('account') or not row.get('password'):raise
    login=call('/api/v1/base/homeUser/accountPasswordLogin',{
     'account':row['account'],'password':row['password']},login=True)
    token=(login.get('data') or {}).get('token')
    if not token:raise OfficialChannelError('官方账号登录失败',401)
    row['token']=str(token);verify_router()
   body={'projectId':row['officialProjectId']}
   if resource=='homeAp/getTunnelUrl':body['sn']=key.sn
   else:
    if not station_ip:raise ValueError('Missing current-project station')
    body['ip' if resource.endswith('checkStaRdp') else 'staIp']=station_ip
   # Never retry a tunnel allocation on timeout: it may already have committed.
   value=call(PATHS[resource],body)
   return {k:value.get(k) for k in ('code','data','msg')}
  except (requests.RequestException,ValueError) as error:
   raise OfficialChannelError('通道服务暂时无法连接') from error
  finally:
   if session is not None:session.close()
   if locked:self.locks[identity].release()
   self.slots.release()
'''
(ROOT/'shinya_hub/official_channels.py').write_text(MODULE,encoding='utf8')

p=ROOT/'shinya_hub/api.py';s=p.read_text(encoding='utf8')
s=s.replace('from .stun_bridge import StunError','from .stun_bridge import StunError\nfrom .official_channels import OfficialChannelError, PATHS as OFFICIAL_CHANNEL_PATHS')
s=s.replace('allow_legacy_tokens=True, stun_bridge=None):','allow_legacy_tokens=True, stun_bridge=None, official_channels=None):')
anchor='            if resource == "apMonitor/getProjectList2":'
routes='''            if resource in OFFICIAL_CHANNEL_PATHS:
                if request.method != 'POST':return reply(code=405,message='通道请求方式无效')
                if not principal.can_write:raise PermissionError('当前账号没有建立通道的权限')
                key=selected_device(principal,raw)
                if official_channels is None:return reply(code=409,message='当前项目尚未绑定官方通道账号')
                station_ip=None
                if resource.startswith('remoteAccess/'):
                    project=selected_project(principal,raw)
                    station_ip=str(raw.get('ip') or raw.get('staIp') or '')
                    candidates=station_snapshots(store,principal.tenant,project)
                    if not station_ip or not any(str(s.get('ipaddr'))==station_ip and s.get('state')==1
                                                 for s in candidates):
                        raise PermissionError('目标设备不属于当前项目的在线终端')
                value=official_channels.request(key,resource,station_ip)
                return reply(value.get('data'),code=value.get('code',0),message=value.get('msg') or '')
            if resource.startswith('childProtection/getStaListWithMainMac/'):
                path_project=resource.rsplit('/',1)[1]
                if raw.get('projectId') and str(raw['projectId'])!=path_project:
                    raise PermissionError('设备选择不属于当前项目')
                key=selected_device(principal,{**raw,'projectId':path_project})
                cached=store.cached(key.tenant,key.project,key.sn,'hub.children.members')
                if cached is None:return reply(code=503,message='尚未取得儿童管理的设备数据')
                rows=cached['value']['data'].get('rows',[])
                from .identifiers import app_mac
                macs=sorted({app_mac(mac) for row in rows for mac in row.get('mergeMacs',[])
                             if mac_key(mac)})
                return reply(macs,cachedAt=cached['observedAt'])
'''
assert s.count(anchor)==1;s=s.replace(anchor,routes+anchor)
anchor='                    project["role"] = "OWNER" if principal.can_write else "VIEWER"'
enrich='''                    project['apList']=[device_snapshot(store,DeviceKey(principal.tenant,project['projectId'],d['sn']),d)
                                       for d in project['apList']]
'''
assert s.count(anchor)==1;s=s.replace(anchor,enrich+anchor)
s=s.replace('        except StunError as error:','        except OfficialChannelError as error:\n            return reply(code=error.code,message=str(error))\n        except StunError as error:')
p.write_text(s,encoding='utf8')
p=ROOT/'shinya_hub/runtime.py';s=p.read_text(encoding='utf8')
s=s.replace('from .stun_bridge import StunBridge','from .stun_bridge import StunBridge\nfrom .official_channels import OfficialChannels')
s=s.replace('stun_bridge=StunBridge(settings.get("stunBindings", [])))',
'''stun_bridge=StunBridge(settings.get("stunBindings", [])),
                     official_channels=OfficialChannels(settings.get("officialBindings", [])))''')
p.write_text(s,encoding='utf8')
print('Owned WEB/RDP adapters and original child selection reads installed')
