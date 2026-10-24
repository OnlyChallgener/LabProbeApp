"""Read/import saved targets from the authorized directory; no tunnel allocation."""
import os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json,time
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceKey
from shinya_hub.official_channels import OfficialChannels
from shinya_hub.penetration import PenetrationService
cfg=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'));channels=OfficialChannels(cfg.get('officialBindings',[]));service=PenetrationService(store,channels)
try:
 for identity in channels.bindings:
  if identity[2]!='G1TD8RX039025':continue
  key=DeviceKey(*identity);start=time.monotonic();result=service.request(key,{'operation':'syncHistory'})
  print(json.dumps({'sn':key.sn,'records':len(result['rules']),'seconds':round(time.monotonic()-start,2),
    'rows':[{k:r.get(k) for k in ('name','targetHost','targetPort','applicationType','state','expiresAt','endpointHost','endpointPort','targetDevice')} for r in result['rules']]},ensure_ascii=False))
finally:service.close()
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=50)
 out=Path('test/_analysis/reyee-nat-2026-10-13/history-live-proof.json');out.write_bytes(raw);print(raw.decode())
finally:c.close()
