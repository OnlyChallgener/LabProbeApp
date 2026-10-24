"""Read the deployed panel/scan contract, without scanning or optimizing radios."""
import json,os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json
from pathlib import Path
from datetime import datetime
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceDriver,DeviceRegistry,DeviceKey
from shinya_hub.api import create_app,Principal,token_digest
from shinya_hub.daily import CHINA
cfg=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'));registry=DeviceRegistry(store)
try:
 for device in cfg['devices']:
  key=DeviceKey(device['tenant'],device['project'],device['sn']);registry.register(key,DeviceDriver(device['address'],device['password'],device['family'],expected_sn=key.sn))
 app=create_app(store,registry,{token_digest('channel-read-proof'):Principal(key.tenant,'read-only')});client=app.test_client();proof=[]
 try:
  for device in cfg['devices']:
   key=DeviceKey(device['tenant'],device['project'],device['sn']);body={'projectId':key.project,'sn':key.sn};values={}
   for route in ('device/state/getLastScan','device/channel/getLastChannelOptimizeResult','device/channel/getCurrentChannelNew','game/accelerate/getGameLog'):
    response=client.post('/api/v1/'+route,json=body,headers={'Authorization':'Bearer channel-read-proof'}).get_json();assert response['code']==0,response
    values[route]=response['data']
   scan=values['device/state/getLastScan'];panel=values['device/channel/getLastChannelOptimizeResult']
   expected=datetime.fromtimestamp(scan['scanTime']/1000,CHINA).strftime('%Y-%m-%d %H:%M:%S') if scan.get('scanTime') else None
   assert panel['lastScanTime']==expected
   assert values['device/channel/getCurrentChannelNew']['lastScanTime']==expected
   cached=store.cached(key.tenant,key.project,key.sn,'hub.channel.optimize.5G')
   result=(cached or {}).get('value',{})
   proof.append({'sn':key.sn,'scanTime':scan.get('scanTime'),'panelTime':panel['lastScanTime'],'inScan':scan.get('inScan'),
    'lastConfirmedChannels':{k:result.get(k) for k in ('beforeChannel','afterChannel','optimizedAt')},
    'logRows':len(values['game/accelerate/getGameLog']['gameLogDetailList'])})
  print(json.dumps(proof))
 finally:
  for name in ('shinya_game_reports','shinya_game_environment','shinya_children','shinya_diagnosis'):app.extensions[name].close()
finally:registry.close()
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=50)
 p=Path('test/_analysis/oct13-official-game/channel-log-unification/api-proof.json');p.write_bytes(raw);print(raw.decode())
finally:c.close()
