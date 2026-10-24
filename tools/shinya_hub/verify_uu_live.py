import json,os
from pathlib import Path
from remote_transport import connect_nas,run
CODE=r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.devices import DeviceDriver,DeviceRegistry,DeviceKey
from shinya_hub.store import ProjectStore
from shinya_hub import uu_acceleration as uu
cfg=ConfigVault(Path('/config')).load();d=next(v for v in cfg['devices'] if v['sn']=='G1TD8RX039025')
k=DeviceKey(d['tenant'],d['project'],d['sn']);driver=DeviceDriver(d['address'],d['password'],d['family'],expected_sn=k.sn)
r=DeviceRegistry(ProjectStore(Path('/data/shinya.sqlite')));r.register(k,driver);before=driver.execute(uu.GET)
original=before['enable']=='true';proof={'sn':k.sn,'version':before['appver']}
try:
 v=uu.change(r,k,{'option':'onoff','enable':'off' if original else 'on'});assert v['verified'];proof['toggled']=v
finally:
 v=uu.change(r,k,{'option':'onoff','enable':'on' if original else 'off'});after=driver.execute(uu.GET)
 assert after['enable']==before['enable'] and after['memlimit']==before['memlimit']
 proof['restored']=True;r.close()
print(json.dumps(proof))
'''
c=connect_nas()
try:
 wrapped='try:\n'+''.join(' '+line+'\n' for line in CODE.splitlines())+'except Exception as e:\n print(json.dumps({"error":type(e).__name__,"message":str(e)[:180]}))\n'
 v=run(c,'docker exec -i shinya-hub python -',stdin=wrapped.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=55)
 p=Path('test/_analysis/oct13-official-game/uu-controls');p.mkdir(exist_ok=True);(p/'native-proof.json').write_bytes(v);print(v.decode())
 assert not json.loads(v).get('error'),'Native verification failed'
finally:c.close()
