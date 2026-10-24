"""Read the authorized project directory; never allocate or alter a tunnel."""
import os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.devices import DeviceKey
from shinya_hub.official_channels import OfficialChannels
cfg=ConfigVault(Path('/config')).load();channels=OfficialChannels(cfg.get('officialBindings',[]))
out=[]
for identity,row in channels.bindings.items():
 if identity[2]!='G1TD8RX039025':continue
 key=DeviceKey(*identity);s=channels.macc.factory();s.trust_env=False
 try:
  channels.macc.authenticated(s,row,key)
  value=channels.macc.call(s,'/tunnel/custom_terminal/list?groupId='+str(int(row['officialProjectId'])),'GET')
  fields={'id','name','groupId','targetSn','sn','remoteClient','remoteHost','remotePort','remoteService','applicationType','type','ip','port','terminalName','terminalIp','terminalPort','deviceType','typeLevel2','appId','terminalType','tunnelId','tunnelStatus','finishTime','vaildTime','validTime','localHost','localPort','endTime'}
  rows=value.get('list',value.get('data',[]));rows=rows if isinstance(rows,list) else []
  out.append({'sn':key.sn,'code':value.get('code'),'keys':list(value),'rows':[{'keys':list(v),'value':{k:v[k] for k in fields if k in v}} for v in rows if isinstance(v,dict)]})
  tunnels=channels.macc.call(s,'/tunnel/get_list','POST',{'groupId':int(row['officialProjectId'])})
  out.append({'tunnels':[{k:v[k] for k in fields if k in v} for v in tunnels.get('list',[]) if isinstance(v,dict)]})
  for saved in rows:
   if not isinstance(saved,dict) or not saved.get('tunnelId'):continue
   detail=channels.macc.call(s,'/tunnel/get_list','POST',{'tunnelId':saved['tunnelId']})
   out.append({'name':saved.get('name'),'detailCode':detail.get('code'),'detail':[{k:v[k] for k in fields if k in v} for v in detail.get('list',[]) if isinstance(v,dict)]})
 finally:s.close()
print(json.dumps(out,ensure_ascii=False))
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=45)
 out=Path('test/_analysis/reyee-nat-2026-10-13/authorized-directory.json');out.write_bytes(raw);print(raw.decode())
finally:c.close()
