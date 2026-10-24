"""Read the deployed, authenticated tools API without exporting credentials."""
import json, os
from pathlib import Path
from remote_transport import connect_nas
from eweb_hub_remote import run

PROBE = r'''
import json,os,yaml,requests
from pathlib import Path
c=yaml.safe_load(Path(os.environ.get('CONFIG_PATH','/app/config/config.yaml')).read_text()) or {}
token=os.environ.get('APP_TOKEN') or c.get('server',{}).get('app_token')
if not token or token=='change-app-token':raise RuntimeError('Token unavailable')
s=requests.Session();s.trust_env=False;s.headers['Authorization']='Bearer '+token
base='http://127.0.0.1:58543/api/v1/'
def post(path,body):
    v=s.post(base+path,json=body,timeout=20).json()
    if v.get('code')!=0:raise RuntimeError('API rejected tools request: '+str(v.get('code')))
    return v['data']
projects=post('apMonitor/getProjectList2',{})['dataList'];out=[]
for p in projects:
    for d in p.get('apList',[]):
        scope={'projectId':p['projectId'],'apSn':d['sn']}
        data=post('hub/stun',{**scope,'operation':'list'})
        row={'sn':d['sn'],'model':d.get('model'),'rules':len(data.get('rules',[])),
             'agentOnline':data.get('agentOnline'),
             'states':[r.get('actualState') for r in data.get('rules',[])]}
        try:
            beta=post('hub/beta',{**scope,'force':1})
            row['beta']={'current':beta.get('cur'),'result':beta.get('new',{}).get('msg'),
                         'available':bool(beta.get('new',{}).get('firmwareList'))}
        except Exception as e:row['betaError']=type(e).__name__
        out.append(row)
print(json.dumps(out))
'''

if __name__=='__main__':
    c=connect_nas()
    try:
        result=json.loads(run(c,'docker exec -i labprobe-hub python -',stdin=PROBE.encode(),
                              sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=58))
        path=Path(__file__).resolve().parents[2]/'test/_analysis/oct09-implementation/deployed-tools-read.json'
        path.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result))
    finally:c.close()
