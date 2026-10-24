"""Compare the existing BE72 floor result with one render at its current positions."""
import os,json
from pathlib import Path
from remote_transport import connect_nas,run
PROBE=r'''
import json,requests
from pathlib import Path
from shinya_hub.vault import ConfigVault
config=ConfigVault(Path('/config')).load()
rows=config['officialBindings']
row=next(v for v in rows if v['sn']=='G1TD8RX039025')
from shinya_hub.official_channels import OfficialChannels
channels=OfficialChannels(rows)
row=channels.bindings[(row['tenant'],row['project'],row['sn'])]
s=requests.Session();s.trust_env=False
def authcall(path,body,login=False):
 return s.post(channels.BASE+path,json=body,headers={'type':'shinyaApp'},timeout=20).json()
channels.fresh_official_session(row,authcall)
headers={'Authorization':'Bearer '+row['token'],'type':'shinyaApp','projectId':str(row['officialProjectId'])}
base='https://shinyaapp.ruijie.com.cn/api/v1/netquality/signal/simulation/'
r=s.get(base+'getSignalSimulationResult',headers=headers,params={'projectId':row['officialProjectId']},timeout=25).json()
out={'readCode':r.get('code'),'readMessage':r.get('msg')}
data=r.get('data') or {}
out['resultKeys']=list(data);out['result']={k:data.get(k) for k in ('area','frequency','apPos','apMode','mainApIdx','remainingSimulationsCount')}
if r.get('code')==0 and data.get('apPos'):
 body={'projectId':row['officialProjectId'],**{k:data[k] for k in ('area','frequency','apPos','apMode')}}
 v=s.post(base+'signalSimulationRender',headers=headers,json=body,timeout=55).json()
 out['renderCode']=v.get('code');out['renderMessage']=v.get('msg');out['renderKeys']=list(v.get('data') or {})
 print(json.dumps(out))
else:print(json.dumps(out))
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=PROBE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=90)
 v=json.loads(raw);Path('test/_analysis/oct12-followup/floor-render.json').write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(v,ensure_ascii=False))
finally:c.close()
