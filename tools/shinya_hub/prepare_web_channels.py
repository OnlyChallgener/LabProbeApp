"""Patch WEB authentication against the installed adapter, retaining all MACC paths."""
from pathlib import Path

ROOT=Path('test/_analysis/oct12-signal-tools/scan')
source=ROOT/'baseline/official_channels.py'
out=ROOT/'deployment/official_channels.py'
s=source.read_text(encoding='utf8')
s=s.replace('import threading','import copy\nimport hashlib\nimport json\nimport math\nfrom urllib.parse import urlsplit\nimport threading',1)
s=s.replace('  self.tunnels={}','  self.tunnels={}\n  self.web_links={}\n  self.account_locks={}\n  self.account_tokens={}',1)
s=s.replace("   self.bindings[identity]=row;self.locks[identity]=threading.Lock()", "   self.bindings[identity]=row;self.locks[identity]=threading.Lock()\n   self.account_locks.setdefault(self.account_key(row),threading.Lock())")
point=s.index(' def remote_desktop_available')
s=s[:point]+''' def account_key(self,row):
  # Sharing the refreshed official session avoids competing logins for the
  # same account. Tenant remains part of the boundary; no HUB token is used.
  return (row['tenant'],hashlib.sha256((str(row.get('account',''))+'\\0'+str(row.get('password',''))).encode()).hexdigest())

 def fresh_official_session(self,row,call,force=False):
  if not row.get('account') or not row.get('password'):return
  account=self.account_key(row)
  with self.account_locks[account]:
   cached=self.account_tokens.get(account)
   if not force and cached and cached['until']>self.clock():
    row['token']=cached['token'];return
   login=call('/api/v1/base/homeUser/accountPasswordLogin',{
    'account':row['account'],'password':row['password']},login=True)
   token=(login.get('data') or {}).get('token')
   if not isinstance(token,str) or not token:raise OfficialChannelError('官方账号登录失败',401)
   self.account_tokens[account]={'token':token,'until':self.clock()+900}
   for bound in self.bindings.values():
    if self.account_key(bound)==account:bound['token']=token

 def validated_web_link(self,value,key):
  # Vendor replies can put an encrypted router password in both the URL and
  # msg. Keep this opaque data in memory only; verify the returned target SN.
  address=value.get('data')
  try:
   meta=json.loads(value.get('msg') or '{}')
   parsed=urlsplit(address)
   port=int(meta['port']);started=float(meta['finishTime']);seconds=float(meta['validTime'])
   expiry=started/1000+seconds
  except (ValueError,TypeError,KeyError):raise OfficialChannelError('WEB 通道响应格式异常')
  if str(meta.get('sn'))!=key.sn or str(meta.get('staIp'))!='127.0.0.1' or int(meta.get('remotePort',0))!=80:
   raise OfficialChannelError('WEB 通道目标与当前路由器不匹配',403)
  if meta.get('status')!='ESTABLISHED' or parsed.scheme not in ('http','https') or not parsed.hostname \\
      or parsed.hostname!=str(meta.get('ip')) or parsed.port!=port or not 1<=port<=65535 \\
      or parsed.username or parsed.password or not all(math.isfinite(n) for n in (started,seconds)) \\
      or not 0<seconds<=10800 or expiry<=self.clock()+10:
   raise OfficialChannelError('WEB 通道尚未建立或已过期',409)
  return {'value':{k:value.get(k) for k in ('code','data','msg')},'until':expiry-15}

''' +s[point:]
s=s.replace("locked=self.locks[identity].acquire(blocking=False)","locked=self.locks[identity].acquire(timeout=20)")
s=s.replace("   row=self.bindings[identity]", "   row=self.bindings[identity]\n   if resource=='homeAp/getTunnelUrl':\n    cached=self.web_links.get(identity)\n    if cached and cached['until']>self.clock():return copy.deepcopy(cached['value'])",1)
s=s.replace('timeout=(4,12),allow_redirects=False)',"timeout=(4,50 if path.endswith(('getTunnelUrl','getRdpTunnel')) else 12),allow_redirects=False)")
s=s.replace("    data=value.get('data') or {}\n    if not isinstance(data,dict)", "    data=value.get('data')\n    # Some expired vendor sessions return code=0,data=null instead of 401.\n    if data is None:raise OfficialChannelError('官方账号授权已失效',401)\n    if not isinstance(data,dict)")
s=s.replace('   try:verify_router()', '   self.fresh_official_session(row,call)\n   try:verify_router()')
a=s.index("    login=call('/api/v1/base/homeUser/accountPasswordLogin'",s.index('   try:verify_router()'))
b=s.index("   body={'projectId'",a)
s=s[:a]+"    self.fresh_official_session(row,call,force=True);verify_router()\n"+s[b:]
s=s.replace('   value=call(PATHS[resource],body)',"   value=call(PATHS[resource],body)\n   if resource=='homeAp/getTunnelUrl':\n    cached=self.validated_web_link(value,key);self.web_links[identity]=cached\n    return copy.deepcopy(cached['value'])")
out.write_text(s,encoding='utf8')
print('Prepared refreshed official authentication and validated expiring WEB links')
