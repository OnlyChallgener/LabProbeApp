import os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json
from pathlib import Path
from shinya_hub.store import ProjectStore
from shinya_hub.vault import ConfigVault
from shinya_hub.signal_scan import RESULT
cfg=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'))
for d in cfg['devices']:
 if d['sn']=='G1TD8RX039025':
  value=store.cached(d['tenant'],d['project'],d['sn'],RESULT)['value'];value['inScan']=None
  print(json.dumps({'code':0,'data':value},ensure_ascii=False))
'''
c=connect_nas()
try:
 raw=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD']);Path('test/_analysis/oct13-official-game/live-scan-response.json').write_bytes(raw);print('Captured actual completed survey fixture')
finally:c.close()
