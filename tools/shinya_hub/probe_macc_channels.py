"""Read only explicitly owned router details from the authorized MACC session."""
import requests,json,sys,time
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.vault import ConfigVault
c=requests.Session();c.trust_env=False
for row in ConfigVault(Path('test/_keys/shinya-macc-session')).load()['cookies']:c.cookies.set(**row)
def api(path,method='GET',params=None):
 body={'api':path,'method':method,'module':'default','querys':{'lang':'zh','cloudType':'smb'}}
 if params is not None:body['params']=params
 r=c.post('https://noc.ruijie.com.cn/webproxy/common/api?'+path.split('?')[0],json=body,timeout=(4,15));r.raise_for_status();return r.json()
proof={'observedAt':time.time(),'routers':[]}
for sn in ('G1TD8RX039025','G1TQ88T702111'):
 value=api('/maint/device/'+sn)
 row={'sn':sn,'code':value.get('code'),'fields':sorted(value)}
 # Keep router ownership and model fields only; never export access secrets.
 data=value.get('data') or value
 row['dataFields']=sorted(data) if isinstance(data,dict) else []
 if isinstance(data,dict):row.update({k:data.get(k) for k in ('serialNumber','sn','groupId','productClass','onlineStatus','name','softwareVersion')})
 proof['routers'].append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
Path('test/_analysis/oct10-priority/macc-owned-routers.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
