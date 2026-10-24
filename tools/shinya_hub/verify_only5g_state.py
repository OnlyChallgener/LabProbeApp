"""Read actual band rules and check the authenticated original APP response."""
import json,os
from pathlib import Path
from remote_transport import connect_nas,run

CODE=r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.devices import DeviceDriver,DeviceRegistry,DeviceKey
from shinya_hub.store import ProjectStore
from shinya_hub.station_controls import APSELECT,only5g_rows
from shinya_hub.api import create_app,Principal,token_digest
settings=ConfigVault(Path('/config')).load()
store=ProjectStore(Path('/data/shinya.sqlite'));registry=DeviceRegistry(store)
proof=[]
try:
 for device in settings['devices']:
  key=DeviceKey(device['tenant'],device['project'],device['sn'])
  driver=DeviceDriver(device['address'],device['password'],device['family'],expected_sn=key.sn)
  registry.register(key,driver)
  native=driver.execute(APSELECT)
  app=create_app(store,registry,{token_digest('only5g-read-proof'):Principal(key.tenant,'read-only')})
  response=app.test_client().post('/api/v1/eSportAccelerate/getStaOnly5GList',
   headers={'Authorization':'Bearer only5g-read-proof'},json={'projectId':key.project,'apSn':key.sn}).get_json()
  assert response['code']==0,response
  assert response['data']==only5g_rows(native),response
  assert all(isinstance(mac,str) and len(mac)==12 for mac in response['data'])
  proof.append({'sn':key.sn,'native':native,'appMacs':response['data'],'readOnlyVerified':True})
 print(json.dumps(proof,ensure_ascii=False))
finally:registry.close()
'''
def main():
 c=connect_nas()
 try:
  result=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=60)
  value=json.loads(result)
  out=Path('test/_analysis/oct13-official-game/only5g-state');out.mkdir(parents=True,exist_ok=True)
  (out/'native-api-proof.json').write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
  print(result.decode())
 finally:c.close()
if __name__=='__main__':main()
