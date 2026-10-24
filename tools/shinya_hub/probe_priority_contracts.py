"""Read current bridge contracts; never output tokens, passwords or tunnel URLs."""
import json, os, sys
from pathlib import Path
sys.path.insert(0, 'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect, run

NATIVE = r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.devices import DeviceDriver
from shinya_hub.commands import UFCommand
from shinya_hub.store import ProjectStore
c=ConfigVault(Path('/config')).load(); out=[]
for d in c['devices']:
 driver=DeviceDriver(d['address'],d['password'],d['family'],expected_sn=d['sn'])
 row={'family':d['family']}
 for module,data in [('user_list',{'devType':'all','dataType':'timely'}),('rcgame',None)]:
  try:
   value=driver.execute(UFCommand('dev_sta','get',module,data))
   row[module]=value
  except Exception as e:row[module]={'error':type(e).__name__}
 out.append(row)
print(json.dumps(out))
'''
LOCAL = r'''
import json,os,requests,yaml
from pathlib import Path
from official_child_guard import OfficialGuardClient
from assistant.security import decrypt_secret_with_migration
c=yaml.safe_load(Path(os.environ.get('CONFIG_PATH','/app/config.yaml')).read_text()) if not os.environ.get('APP_TOKEN') else {}
token=os.environ.get('APP_TOKEN') or c.get('server',{}).get('app_token')
s=requests.Session();s.trust_env=False;s.headers['Authorization']='Bearer '+token
port=os.environ.get('PORT','58443');out={}
for suffix in ('overview','official/auth'):
 r=s.get('http://127.0.0.1:'+port+'/api/router/child-guard/'+suffix,timeout=(2,8))
 out[suffix]={'status':r.status_code,'body':r.json()}
paths=[Path(os.environ.get('DATA_DIR','/app/data'))/'official_child_guard_accounts.json',Path('/data/official_child_guard_accounts.json')]
p=next((p for p in paths if p.is_file()),None)
bindings=[json.loads(decrypt_secret_with_migration(v)[0]) for v in json.loads(p.read_text()).values()] if p else []
for binding in bindings:
 client=OfficialGuardClient(binding['token'],binding);client.session.trust_env=False
 try:out['gameStatistics']=client.request('POST','/game/accelerate/getGameStatistics',data={'sn':binding['serial'],'apSn':binding['serial']})
 except Exception as e:out['gameStatistics']={'error':getattr(e,'code',type(e).__name__)}
print(json.dumps(out))
'''

if __name__=='__main__':
 client=connect(os.environ['SHINYA_NAS_HOST'],int(os.environ['SHINYA_NAS_PORT']),os.environ['SHINYA_NAS_USER'],os.environ['SHINYA_NAS_PASSWORD'])
 out={}
 try:
  for name,program in [('shinya-hub',NATIVE),('labprobe-hub',LOCAL),('labprobe-hub-be50',LOCAL)]:
   try:out[name]=json.loads(run(client,'docker exec -i '+name+' python -',stdin=program.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=55))
   except Exception as e:out[name]={'error':type(e).__name__}
 finally:client.close()
 root=Path(__file__).resolve().parents[2]/'test/_analysis/oct10-priority';root.mkdir(parents=True,exist_ok=True)
 (root/'contracts.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf8')
 for name,result in out.items():
  if isinstance(result,list):
   print(json.dumps({'service':name,'devices':[{'family':d['family'],'stationFields':sorted(set(k for r in d.get('user_list',{}).get('list',[]) for k in r)), 'rateSamples':[{k:r.get(k) for k in ('mac','rxrate','txrate','band')} for r in d.get('user_list',{}).get('list',[])][:3], 'gameFields':sorted(d.get('rcgame',{}))} for d in result]}))
  else:print(json.dumps({'service':name,'overviewFields':sorted(result.get('overview',{}).get('body',{})), 'auth':result.get('official/auth',{}),'gameFields':sorted(result.get('gameStatistics') or {}),'error':result.get('error')}))
