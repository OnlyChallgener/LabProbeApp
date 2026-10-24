"""Run real firmware checks through the deployed HUB, never print credentials."""
import json
import os
import time
from pathlib import Path
from remote_transport import connect_nas,run

credentials={'account':os.environ.get('SHINYA_ADMIN_ACCOUNT','Demon'),
             'password':os.environ['SHINYA_ADMIN_PASSWORD']}
script='credentials='+repr(credentials)+'\n'+r'''
import json,time,requests
from concurrent.futures import ThreadPoolExecutor
s=requests.Session();s.trust_env=False
base='http://127.0.0.1:58543/api/v1/'
login=s.post(base+'hub/account/login',json=credentials,timeout=8).json()
assert login['code']==0
s.headers['Authorization']='Bearer '+login['data']['token']
def post(path,data):
    value=s.post(base+path,json=data,timeout=12).json()
    assert value['code']==0,(path,value['code'],value.get('msg'))
    return value['data']
projects=post('apMonitor/getProjectList2',{})['dataList']
proof=[]
for project in projects:
    data={'projectId':project['projectId'],'operation':'start'}
    started=post('hub/diagnosis',data)
    proof.append({'projectId':project['projectId'],'name':project['projectName'],
                  'jobId':started['jobId'],'state':started['state'],'realtimeMs':[]})
deadline=time.monotonic()+115
while time.monotonic()<deadline:
    for row in proof:
        if row['state'] in ('complete','failed'):continue
        before=time.monotonic()
        telemetry=post('homeAp/getStaList2',{'projectId':row['projectId']})
        row['realtimeMs'].append(round((time.monotonic()-before)*1000))
        status=post('hub/diagnosis',{'projectId':row['projectId'],'jobId':row['jobId']})
        row.update(status)
    if all(r['state'] in ('complete','failed') for r in proof):break
    time.sleep(2)
for row in proof:
    row.pop('jobId',None)
    assert row['state']=='complete',row
print(json.dumps(proof,ensure_ascii=False))
# Release the temporary validation login, preserving the APP's active session.
s.post(base+'hub/account/logout',json={'refreshToken':login['data']['refreshToken']},timeout=8)
'''
client=connect_nas()
try:
    result=json.loads(run(client,'docker exec -i shinya-hub python -',stdin=script.encode(),
                          sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=150))
finally:client.close()
output=Path('test/_analysis/oct10-pages/diagnosis-live.json')
output.write_text(json.dumps({'observedAt':time.time(),'projects':result},ensure_ascii=False,indent=2),encoding='utf8')
for row in result:
    print(json.dumps({'project':row['name'],'state':row['state'],'checks':row['report']['checkedCount'],
                      'errors':row['report']['errorCount'],'maxTelemetryMs':max(row['realtimeMs'])},ensure_ascii=False))
