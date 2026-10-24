"""Audit display metadata for all applications observed on guarded devices."""
import json,os
from pathlib import Path
from remote_transport import connect_nas,run

CODE=r'''
import json,requests,hashlib
from datetime import datetime,timedelta
from zoneinfo import ZoneInfo
s=requests.Session();s.trust_env=False
base='http://127.0.0.1:58543/api/v1/'
v=s.post(base+'base/homeUser/accountPasswordLogin',json={'account':ACCOUNT,'password':PASSWORD},timeout=10).json()
assert v['code']==0
s.headers['Authorization']='Bearer '+(v['data'].get('token') or v['data'].get('accessToken'))
def get(path,p):
 v=s.get(base+path,params=p,timeout=25).json()
 assert v['code']==0,(path,v.get('msg'))
 return v['data']
p={'mainApSn':'G1TD8RX039025'}
members=get('netguard/staGuard/getProtectStaListPage',p)['rows']
today=datetime.now(ZoneInfo('Asia/Shanghai')).date()
apps={}
for user in members:
 for n in range(10):
  day=str(today-timedelta(days=n))
  rows=get('netguard/onlineReport/getStaAppReport',{**p,'staId':user['staId'],'day':day})['rows']
  for row in rows:
   name=row['appName']
   apps.setdefault(name,{'name':name,'id':row['appId'],'icon':row['appLink'],'deviceDays':0})['deviceDays']+=1
assert all(r['icon'] for r in apps.values()),'Observed application has no icon'
from shinya_hub.catalog import AppCatalog
verified=[]
for row in AppCatalog().extra:
 if '/assets/icons/' not in row['link']:continue
 digest=row['link'].rsplit('/',1)[-1]
 asset=s.get('http://127.0.0.1:58543/assets/icons/'+digest,timeout=15)
 assert asset.status_code==200 and hashlib.sha256(asset.content).hexdigest()==digest,row['showName']
 verified.append(row['showName'])
print(json.dumps({'guardCount':len(members),'start':str(today-timedelta(days=9)),'end':str(today),
 'apps':sorted(apps.values(),key=lambda r:r['name']),'ownHubIconsVerified':verified},ensure_ascii=False),flush=True)
'''

def main():
    code=CODE.replace('ACCOUNT',repr(os.environ['SHINYA_ADMIN_ACCOUNT'])).replace('PASSWORD',repr(os.environ['SHINYA_ADMIN_PASSWORD']))
    code='try:\n'+''.join('    '+line+'\n' for line in code.splitlines())+'except Exception:\n    import traceback\n    print(traceback.format_exc(),flush=True)\n'
    client=connect_nas()
    try:
        out=run(client,'docker exec -i shinya-hub python -',stdin=code.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=160)
        proof=json.loads(out)
        Path('test/_analysis/oct11-children/icon-audit.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps(proof,ensure_ascii=False),flush=True)
    finally:client.close()

if __name__=='__main__':main()
