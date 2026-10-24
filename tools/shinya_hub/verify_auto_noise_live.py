"""Read deployed automatic-noise state; do not trigger radio optimization."""
import json,os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json,time
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceDriver,DeviceRegistry,DeviceKey
from shinya_hub.game_noise import AutoNoiseReduction,TELEMETRY,STATE,has_active_game
from shinya_hub.game_acceleration import preferences
cfg=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'));registry=DeviceRegistry(store);noise=AutoNoiseReduction(registry)
try:
 proof=[]
 for device in cfg['devices']:
  key=DeviceKey(device['tenant'],device['project'],device['sn'])
  registry.register(key,DeviceDriver(device['address'],device['password'],device['family'],expected_sn=key.sn))
  cached=store.cached(key.tenant,key.project,key.sn,TELEMETRY);state=store.cached(key.tenant,key.project,key.sn,STATE)
  current=cached['value'] if cached else {};now=time.time()
  proof.append({'sn':key.sn,'preferences':preferences(store,key),'activityAge':now-current['observedAt'] if current.get('observedAt') else None,
   'activeGame':has_active_game(current,now),'eligible':noise.eligible(key,now),'state':state['value'] if state else None})
 print(json.dumps(proof))
finally:noise.close();registry.close()
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=40)
 out=Path('test/_analysis/oct13-official-game/automatic-noise');out.mkdir(exist_ok=True);(out/'live-state.json').write_bytes(raw);print(raw.decode())
finally:c.close()
