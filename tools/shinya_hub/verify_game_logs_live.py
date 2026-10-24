"""Verify deployed read routes and inspect fixed native event locations."""
import json,os
from pathlib import Path
from remote_transport import connect_nas,connect_router,run
OUT=Path('test/_analysis/oct13-official-game/game-log-history')
CODE=r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceDriver,DeviceRegistry,DeviceKey
from shinya_hub.api import create_app,Principal,token_digest
settings=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'));registry=DeviceRegistry(store);proof=[]
try:
 for device in settings['devices']:
  key=DeviceKey(device['tenant'],device['project'],device['sn'])
  registry.register(key,DeviceDriver(device['address'],device['password'],device['family'],expected_sn=key.sn))
 app=create_app(store,registry,{token_digest('log-read-proof'):Principal(key.tenant,'read-only')});client=app.test_client()
 try:
  for device in settings['devices']:
   key=DeviceKey(device['tenant'],device['project'],device['sn']);body={'projectId':key.project,'sn':key.sn}
   values={}
   for endpoint in ('getGameLog','getAccelerateStatistics','getGameStatistics'):
    response=client.post('/api/v1/game/accelerate/'+endpoint,json=body,headers={'Authorization':'Bearer log-read-proof'}).get_json()
    assert response['code']==0,response
    values[endpoint]=response['data']
   assert isinstance(values['getGameLog']['gameLogDetailList'],list)
   assert all(isinstance(values['getAccelerateStatistics'][k],dict) for k in ('antiBother','antiOccupy','antiShake'))
   proof.append({'sn':key.sn,'logRows':len(values['getGameLog']['gameLogDetailList']),'AI':values['getAccelerateStatistics'],'source':values['getGameLog']['source']})
  print(json.dumps(proof,ensure_ascii=False))
 finally:
  for name in ('shinya_game_reports','shinya_game_environment','shinya_children','shinya_diagnosis'):app.extensions[name].close()
finally:registry.close()
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=45)
 (OUT/'api-proof.json').write_bytes(raw);print(raw.decode())
finally:c.close()
c=connect_router('be72')
try:
 assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']=='G1TD8RX039025'
 value=run(c,r"find /tmp/log /tmp/game_wifi /tmp/mvp_edge /etc/init.d /usr/local/lua -maxdepth 2 -type f 2>/dev/null | sed -n '/game\|low_rate\|optimi\|channel\|noise/p'; true")
 (OUT/'be72-native-event-locations.txt').write_bytes(value)
finally:c.close()
