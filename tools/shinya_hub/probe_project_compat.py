"""Read-only validation of live project contracts; never exports credentials."""
import json
import os
from pathlib import Path
from remote_transport import connect_nas
from eweb_hub_remote import run

script = r'''
import json,requests,yaml,os
from pathlib import Path
settings=yaml.safe_load(Path(os.environ.get('CONFIG_PATH','/app/config/config.yaml')).read_text())
token=os.environ.get('APP_TOKEN') or settings['server']['app_token']
s=requests.Session();s.trust_env=False
def post(resource,body):
    return s.post('http://127.0.0.1:58543/api/v1/'+resource,json=body,
                  headers={'Authorization':'Bearer '+token},timeout=20).json()
projects=post('apMonitor/getProjectList2',{})['data']['dataList']
out=[]
for project in projects:
    sns=[d['sn'] for d in project['apList']]
    check=post('homeAp/isSoftVersionUpdateList',{'projectId':project['projectId'],'dataList':[{'sn':sn} for sn in sns]})
    item={'project':project['projectId'],'name':project.get('name'), 'routers':sns,
          'betaStatus':post('homeAp/getBetaStatus',{'projectId':project['projectId']}), 'betaDetection':check}
    item['stun']=[{'sn':sn, 'code':v['code'], 'ruleCount':len((v.get('data') or {}).get('rules',[]))}
                  for sn in sns for v in [post('hub/stun',{'projectId':project['projectId'],'apSn':sn,'operation':'list'})]]
    other=next((sn for p in projects if p['projectId']!=project['projectId'] for sn in [d['sn'] for d in p['apList']]),None)
    if other:
        item['foreignBetaCode']=post('homeAp/isSoftVersionUpdateList',{'projectId':project['projectId'],'dataList':[{'sn':other}]})['code']
        item['foreignStunCode']=post('hub/stun',{'projectId':project['projectId'],'apSn':other,'operation':'list'})['code']
    out.append(item)
print(json.dumps(out,ensure_ascii=False))
'''
client=connect_nas()
try:
    # Execute in existing trusted service; mounted configs are read only in this probe.
    value=run(client,'docker exec -i labprobe-hub python -',stdin=script.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=80)
    result=json.loads(value)
    path=Path('test/_analysis/oct09-implementation/project-contracts-v17.json')
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))
finally:
    client.close()
