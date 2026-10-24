"""Read durable survey state and completed result metadata only."""
import os
from remote_transport import connect_nas,run
CODE=r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.signal_scan import RESULT,STATUS
cfg=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'));out=[]
for d in cfg['devices']:
 scope=(d['tenant'],d['project'],d['sn']);state=store.cached(*scope,STATUS);result=store.cached(*scope,RESULT)
 value=result['value'] if result else {}
 out.append({'sn':d['sn'],'status':state['value'] if state else None,'scanTime':value.get('scanTime'),
             'bands':{k:len((value.get(k) or {}).get('channelSsidList',[])) for k in ('channel24Detail','channel52Detail','channel58Detail')}})
print(json.dumps(out,ensure_ascii=False))
'''
c=connect_nas()
try:print(run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD']).decode())
finally:c.close()
