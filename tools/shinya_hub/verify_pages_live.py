"""Check retained pages against current authenticated project data, without writes."""
import json
import os
import time
from pathlib import Path
from remote_transport import connect_nas, run

credentials = {'account': os.environ['SHINYA_ADMIN_ACCOUNT'],
               'password': os.environ['SHINYA_ADMIN_PASSWORD']}
script = 'credentials=' + repr(credentials) + '\n' + r'''
import json,time,requests
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceRegistry
s=requests.Session();s.trust_env=False
base='http://127.0.0.1:58543/api/v1/'
login=s.post(base+'hub/account/login',json=credentials,timeout=8).json()
assert login['code']==0
s.headers['Authorization']='Bearer '+login['data']['token']
def post(path,data):
    before=time.monotonic()
    value=s.post(base+path,json=data,timeout=8).json()
    assert value['code']==0,(path,value['code'],value.get('msg'))
    return value['data'],round((time.monotonic()-before)*1000,1)
try:
    projects,_=post('apMonitor/getProjectList2',{})
    store=ProjectStore(Path('/data/shinya.sqlite'))
    cfg=ConfigVault(Path('/config')).load()
    proof=[]
    for p in projects['dataList']:
        body={'projectId':p['projectId']}
        rows,ms=post('homeAp/getStaList2',body)
        rate,rms=post('experienceVisual/getApSpeedRate',body)
        quality,qms=post('experienceVisual/getNetStabilizeIndex',body)
        device=next(d for d in cfg['devices'] if d['project']==p['projectId'])
        native=store.cached(device['tenant'],device['project'],device['sn'],
                            DeviceRegistry.resource(UFCommand('dev_sta','get','user_list',None)))
        with store.connect() as db:
            push=db.execute('SELECT observed FROM push_transport WHERE tenant=? AND project=? AND sn=?',
                            (device['tenant'],device['project'],device['sn'])).fetchone()
        stations=rows['sta_list']
        proof.append({'name':p['projectName'],'project':p,'stationCount':len(stations),
            'stationKeys':list(stations[0]) if stations else [],
            'stationsOnline':sum(bool(r.get('online')) for r in stations),
            'nativeOnline':len(native['value'].get('list',[])) if native else None,
            'pushAgeSeconds':round(time.time()-push['observed'],2) if push else None,
            'curveBuckets':len(rate.get('bucketIndices',[])),
            'lastHourRx':rate.get('lastHourRx'),'lastHourTx':rate.get('lastHourTx'),
            'score':quality.get('curRadarScore',{}).get('overallScore'),
            'legend':quality.get('curRadarScore',{}),
            'responseMs':{'stations':ms,'speed':rms,'stability':qms}})
    print(json.dumps(proof,ensure_ascii=False))
finally:
    s.post(base+'hub/account/logout',json={'refreshToken':login['data']['refreshToken']},timeout=8)
'''
client = connect_nas()
try:
    result = json.loads(run(client, 'docker exec -i shinya-hub python -', stdin=script.encode(),
                            sudo_password=os.environ['SHINYA_NAS_PASSWORD'], timeout=50))
finally:
    client.close()
output = Path('test/_analysis/oct10-pages/pages-live.json')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'observedAt':time.time(),'projects':result},
                             ensure_ascii=False,indent=2),encoding='utf8')
for row in result:
    print(json.dumps({k:v for k,v in row.items() if k not in ('project','legend')},ensure_ascii=False))
