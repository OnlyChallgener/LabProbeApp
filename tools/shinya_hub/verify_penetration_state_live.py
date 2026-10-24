"""Create one isolated QA target, observe UI actions, verify and remove it."""
import os,sys
from pathlib import Path
from remote_transport import connect_nas,run

CODE=r'''
import json,secrets,time,requests
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.auth import AccountStore
from shinya_hub.official_channels import OfficialChannels
from shinya_hub.devices import DeviceKey
phase=PHASE
path=Path('/tmp/hub56-penetration-qa.json');tag='HUB56_UI_QA';host='192.168.5.46';port=58543
cfg=ConfigVault(Path('/config')).load();channels=OfficialChannels(cfg.get('officialBindings',[]))
identity=next(i for i in channels.bindings if i[2]=='G1TD8RX039025');key=DeviceKey(*identity);row=channels.bindings[identity]
accounts=AccountStore(Path('/data/accounts.sqlite'));cloud=channels.macc.factory();cloud.trust_env=False
channels.macc.authenticated(cloud,row,key)
def directory():
 v=channels.macc.call(cloud,'/tunnel/custom_terminal/list?groupId='+str(int(row['officialProjectId'])),'GET')
 assert str(v.get('code'))=='0' and isinstance(v.get('list'),list)
 return v['list']
if phase=='prepare':
 assert not path.exists(),'QA state already exists; read or cleanup first'
 assert not any(str(v.get('ip'))==host and str(v.get('port'))==str(port) for v in directory()),'Target already belongs to a saved application'
 value=channels.macc.call(cloud,'/tunnel/custom_terminal/upsert','POST',{'groupId':int(row['officialProjectId']),'name':tag,'ip':host,'port':str(port),'type':'HTTP','icon':''})
 assert str(value.get('code'))=='0','QA saved-target request rejected'
 matches=[v for v in directory() if v.get('name')==tag and str(v.get('ip'))==host and str(v.get('port'))==str(port)]
 assert len(matches)==1,'QA target was not confirmed'
 username='qa-hub56-'+secrets.token_hex(4);password=secrets.token_urlsafe(24)
 aid=accounts.create_account(username,password,key.tenant,'Temporary HUB56 verification',True)
 state={'username':username,'password':password,'accountId':aid,'directoryId':str(matches[0]['id']),'projectId':key.project,'sn':key.sn}
 path.write_text(json.dumps(state));path.chmod(0o600)
else:
 state=json.loads(path.read_text());assert state['sn']==key.sn and state['username'].startswith('qa-hub56-')
api=requests.Session();api.trust_env=False
def post(route,body):
 v=api.post('http://127.0.0.1:58543/api/v1/'+route,json=body,timeout=45).json()
 assert v.get('code')==0,(route,v.get('code'),v.get('msg'))
 return v['data']
login=post('hub/account/login',{'account':state['username'],'password':state['password']});api.headers['Authorization']='Bearer '+login['token']
def pen(action,**extra):return post('hub/penetration',{'projectId':key.project,'sn':key.sn,'operation':action,**extra})
if phase=='prepare':
 result=pen('syncHistory');rule=next(r for r in result['rules'] if str(r.get('directoryId'))==state['directoryId'])
 state['ruleId']=rule['id'];path.write_text(json.dumps(state));path.chmod(0o600)
 print(json.dumps({'phase':phase,'ruleId':rule['id'],'name':rule['name'],'state':rule['state'],'targetDevice':rule['targetDevice']}))
elif phase=='read':
 rows=pen('list')['rules'];rule=next((r for r in rows if r['id']==state['ruleId']),None)
 saved=next((v for v in directory() if str(v['id'])==state['directoryId']),None)
 proof={'phase':phase,'hubExists':rule is not None,'officialExists':saved is not None,'state':rule.get('state') if rule else None,'enabled':rule.get('enabled') if rule else None,'endpoint':rule.get('address') if rule else None,'deviceType':(rule.get('targetDevice') or {}).get('typeLevel2') if rule else None}
 if rule and rule.get('enabled'):
  check=requests.get(rule['address']+'/healthz',timeout=8);health=check.json();proof['publicReachable']=health.get('service')=='shinya-hub' and health.get('ok') is True
 print(json.dumps(proof))
elif phase=='noise':
 scope={'projectId':key.project,'sn':key.sn}
 before=post('game/accelerate/getAccelerateStatistics',scope)['antiBother']
 result=post('game/accelerate/oneStepAiNoiseReduction',scope)
 after=post('game/accelerate/getAccelerateStatistics',scope)['antiBother']
 logs=post('game/accelerate/getGameLog',scope)['gameLogDetailList']
 changed=str(result['beforeChannel'])!=str(result['afterChannel'])
 assert after['todayCnt']==before['todayCnt']+int(changed),(before,after,result)
 assert after['historyCnt']==before['historyCnt']+int(changed),(before,after,result)
 matched=any(v['eventTime']==result['optimizedAt'] and str(result['beforeChannel']) in v['eventDetail'] and str(result['afterChannel']) in v['eventDetail'] for v in logs)
 if changed:assert matched,'Observed channel switch missing from acceleration log'
 print(json.dumps({'phase':phase,'beforeChannel':result['beforeChannel'],'afterChannel':result['afterChannel'],'switched':changed,'beforeCounts':before,'afterCounts':after,'logRecorded':matched}))
elif phase=='cleanup':
 rows=pen('list')['rules'];rule=next((r for r in rows if r['id']==state['ruleId']),None)
 if rule:
  value=pen('delete',ruleId=rule['id'],idempotencyKey='cleanup-'+state['ruleId']);op=value.get('operation')
  if op:
   for attempt in range(60):
    value=pen('status',ruleId=rule['id'],operationId=op['id'])
    if value['operation']['state']=='SUCCEEDED':break
    time.sleep(1)
   else:raise AssertionError('QA cleanup pending; keep protected state for recovery')
 assert not any(str(v.get('id'))==state['directoryId'] for v in directory()),'Official QA target still exists'
 post('hub/account/logout',{'refreshToken':login['refreshToken']})
 with accounts.connect() as db:
  found=db.execute('SELECT username FROM accounts WHERE id=?',(state['accountId'],)).fetchone();assert found and found['username']==state['username']
  db.execute('DELETE FROM sessions WHERE account=?',(state['accountId'],));db.execute('DELETE FROM accounts WHERE id=?',(state['accountId'],))
 path.unlink();print(json.dumps({'phase':phase,'officialDeleted':True,'testAccountRemoved':True}))
else:raise AssertionError('Unknown phase')
if phase!='cleanup':post('hub/account/logout',{'refreshToken':login['refreshToken']})
cloud.close();api.close()
'''
if __name__=='__main__':
 phase=sys.argv[1];assert phase in ('prepare','read','noise','cleanup')
 code=CODE.replace('PHASE',repr(phase));c=connect_nas()
 try:
  raw=run(c,'docker exec -i shinya-hub python -',stdin=code.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=120)
  out=Path('test/_analysis/reyee-nat-2026-10-13/hub56-state-live');out.mkdir(exist_ok=True)
  (out/(phase+'-'+str(__import__('time').time_ns())+'.json')).write_bytes(raw);print(raw.decode())
 finally:c.close()
