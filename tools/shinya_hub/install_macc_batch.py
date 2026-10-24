"""Install the audited current-project MACC adapter in the independent HUB."""
from pathlib import Path
root=Path('D:/Github/ShinyaHub/shinya_hub')
(root/'macc_channels.py').write_bytes(Path('tools/shinya_hub/macc_channels_source.py').read_bytes())
p=root/'official_channels.py'
s=p.read_text(encoding='utf8')
if 'from .macc_channels import' not in s:
    s=s.replace('import requests\n','import requests\nfrom .macc_channels import MaccChannels, MaccError\n')
    s=s.replace('self.slots=threading.BoundedSemaphore(3)', 'self.slots=threading.BoundedSemaphore(3)\n  self.macc=MaccChannels(session_factory)')
    s=s.replace(' def request(self,key,resource,station_ip=None):', ''' def remote_desktop_available(self,key):
  row=self.bindings.get((key.tenant,key.project,key.sn))
  return bool(row and self.macc.enabled(row))

 def request(self,key,resource,station_ip=None):''')
    s=s.replace('row=self.bindings[identity];session=self.factory();session.trust_env=False', '''row=self.bindings[identity]
   if resource=='remoteAccess/getRdpTunnel' and self.macc.enabled(row):
    if not station_ip:raise ValueError('Missing current-project station')
    try:return self.macc.rdp(row,key,station_ip)
    except MaccError as error:raise OfficialChannelError(str(error),error.code) from error
   session=self.factory();session.trust_env=False''')
    p.write_text(s,encoding='utf8')
p=root/'api.py';s=p.read_text(encoding='utf8')
if 'def projected_device' not in s:
    anchor='    def json_string(value):'
    assert anchor in s
    s=s.replace(anchor,'''    def projected_device(key,metadata):
        row=device_snapshot(store,key,metadata)
        if official_channels is not None and official_channels.remote_desktop_available(key):
            row['remotectrl']='1'
            row['capabilities']['gateway']={**row['capabilities']['gateway'],'remotectrl':'1'}
        return row

'''+anchor)
    s=s.replace("device_snapshot(store,DeviceKey(principal.tenant,project['projectId'],d['sn']),d)","projected_device(DeviceKey(principal.tenant,project['projectId'],d['sn']),d)")
    s=s.replace('device_snapshot(store, DeviceKey(principal.tenant, project["projectId"], d["sn"]), d)', 'projected_device(DeviceKey(principal.tenant, project["projectId"], d["sn"]), d)')
    p.write_text(s,encoding='utf8')
print('MACC RDP adapter and owned project capability projection installed')

p=root/'api.py';s=p.read_text(encoding='utf8')
if 'def projected_native' not in s:
    s=s.replace('    def json_string(value):', '''    def projected_native(key,command,value):
        if command.namespace=='dev_cap' and command.module=='gateway' and isinstance(value,dict) and official_channels is not None and official_channels.remote_desktop_available(key):
            return {**value,'remotectrl':'1'}
        return value

    def json_string(value):''')
    s=s.replace('json_string(cached["value"]), cachedAt=cached["observedAt"]','json_string(projected_native(key,command,cached["value"])), cachedAt=cached["observedAt"]')
    s=s.replace('json_string(future.result()))\n            return reply({"cmdId": jobs.add(key, future)})', '''json_string(projected_native(key,command,future.result())))
            from concurrent.futures import Future
            mapped=Future()
            def done(value):
                try:mapped.set_result(projected_native(key,command,value.result()))
                except Exception as error:mapped.set_exception(error)
            future.add_done_callback(done)
            return reply({"cmdId": jobs.add(key, mapped)})''')
    p.write_text(s,encoding='utf8')

p=root/'official_channels.py';s=p.read_text(encoding='utf8')
if "data['endTime']" not in s:
    s=s.replace("value=call(PATHS[resource],body)\n   return", """value=call(PATHS[resource],body)
   if resource=='remoteAccess/getRdpTunnel' and isinstance(value.get('data'),dict):
    data=value['data']
    if 'endTime' not in data and data.get('startTime'):
     data['endTime']=int(data['startTime'])+600000
   return""")
    p.write_text(s,encoding='utf8')
