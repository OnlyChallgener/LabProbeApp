import json,os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceDriver,DeviceRegistry,DeviceKey
from shinya_hub.api import create_app,Principal,token_digest
settings=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'));r=DeviceRegistry(store);proof=[]
try:
 for d in settings['devices']:
  k=DeviceKey(d['tenant'],d['project'],d['sn']);r.register(k,DeviceDriver(d['address'],d['password'],d['family'],expected_sn=k.sn))
  c=create_app(store,r,{token_digest('support-read-proof'):Principal(k.tenant,'read-only')}).test_client()
  v=c.post('/api/v1/game/accelerate/getAccelerateGameList',headers={'Authorization':'Bearer support-read-proof'},json={'projectId':k.project,'sn':k.sn}).get_json()
  assert v['code']==0,v
  assert v['data']['gameList'] and all(x.get('gameType') and x.get('name') and x['link'].startswith('https://') for x in v['data']['gameList']),v
  proof.append({'sn':k.sn,'supportedGames':len(v['data']['gameList']),'source':v['data']['source']})
 print(json.dumps(proof,ensure_ascii=False))
finally:r.close()
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=50)
 Path('test/_analysis/oct13-official-game/game-support/api-proof.json').write_bytes(raw);print(raw.decode())
 logs=run(c,"docker logs --tail 70 shinya-hub 2>&1",sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
 print('\n'.join(l for l in logs.decode(errors='replace').splitlines() if 'getAccelerateGameList' in l or 'support' in l.lower())[-1500:])
finally:c.close()
