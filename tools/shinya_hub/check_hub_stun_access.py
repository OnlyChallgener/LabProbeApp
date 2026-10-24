"""Read public/local HUB HTTP responses from outside and inside the LAN."""
import json,os
from pathlib import Path
from remote_transport import connect_nas,run
PROBE=r'''
import requests,json
s=requests.Session();s.trust_env=False;out=[]
for url in ('http://192.168.5.46:58443/api/status','http://192.168.5.46:58444/api/status','http://111.23.167.101:12317/api/status','http://111.23.167.101:12302/api/status'):
 try:
  r=s.get(url,timeout=5);out.append({'url':url,'status':r.status_code,'responseKeys':list(r.json())})
 except Exception as e:out.append({'url':url,'error':type(e).__name__})
print(json.dumps(out))
'''
c=connect_nas()
try:
 v=json.loads(run(c,'docker exec -i shinya-hub python -',stdin=PROBE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=30))
 Path('test/_analysis/oct12-followup/hub-lan-public.json').write_text(json.dumps(v,indent=2),encoding='utf8');print(json.dumps(v))
finally:c.close()
