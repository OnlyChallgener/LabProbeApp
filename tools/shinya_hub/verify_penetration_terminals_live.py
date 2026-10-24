"""Read the scoped picker inventory without creating targets or tunnels."""
import os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json
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
  value=service.request(DeviceKey(*identity),{'operation':'terminals'})
  print(json.dumps({'sn':identity[2],'count':len(value['terminals']),'rows':[{'name':r['name'],'ip':r['ip'],'type':r['targetDevice'].get('typeLevel2')} for r in value['terminals']]},ensure_ascii=False))
finally:service.close()
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=45)
 Path('test/_analysis/reyee-nat-2026-10-13/terminal-picker-live.json').write_bytes(raw);print(raw.decode())
finally:c.close()
