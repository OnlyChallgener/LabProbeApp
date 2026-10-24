"""Enable only the LED write after real, unchanged-state firmware readback."""
import json
import os
from pathlib import Path
from remote_transport import connect_nas,run

SCRIPT=r'''
import json,time
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceRegistry,DeviceDriver,DeviceKey
from shinya_hub.commands import UFCommand
vault=ConfigVault(Path('/config'));cfg=vault.load();store=ProjectStore(Path('/data/shinya.sqlite'))
registry=DeviceRegistry(store);rows=[];stage='register';family=''
try:
    for d in cfg['devices']:
        family=d['family'];stage='register'
        key=DeviceKey(d['tenant'],d['project'],d['sn'])
        registry.register(key,DeviceDriver(d['address'],d['password'],d['family'],expected_sn=d['sn']))
        with registry.device_operation(key) as driver:
            stage='read-before'
            before=driver.execute(UFCommand('ac_config','get','devLed',None))
        rows.append({'family':family,'stage':stage,'keys':list(before) if isinstance(before,dict) else [],
                     'led':before.get('led_all') if isinstance(before,dict) else None})
        stage='validate-before'
        assert before.get('led_all') in ('close','restore'),d['family']
        stage='same-state-write'
        registry.execute_write(key,UFCommand('ac_config','set','devLed',before))
        with registry.device_operation(key) as driver:
            after=driver.execute(UFCommand('ac_config','get','devLed',None))
        assert after['led_all']==before['led_all'],d['family']
        rows.append({'family':d['family'],'before':before['led_all'],'after':after['led_all'],'readbackVerified':True})
    print(json.dumps({'verified':'ac_config/devLed','routers':rows}))
except Exception as error:
    print(json.dumps({'failed':True,'stage':stage,'family':family,'errorType':type(error).__name__,
        'readbackMismatch':'readback did not match' in str(error),'observations':rows}))
finally:registry.close()
'''
client=connect_nas()
try:
    result=json.loads(Path('test/_analysis/oct10-pages/led-readback.json').read_text())
    observations=result.get('routers',result.get('observations',[]))
    proof=[r for r in observations if r.get('readbackVerified')]
    assert {r['family'] for r in proof}=={'BE72','BE50'}
    assert all(r['before']==r['after'] for r in proof)
    # The serving container mounts configuration read-only. This short-lived,
    # offline helper writes the encrypted vault on its separate host volume.
    save=r'''
import json,time
from pathlib import Path
from shinya_hub.vault import ConfigVault
vault=ConfigVault(Path('/config'));cfg=vault.load()
enabled=cfg.setdefault('enabledWrites',[])
if ['ac_config','devLed'] not in enabled:
    backup=vault.value_path.with_name('devices.enc.before-led-'+str(int(time.time()))+'.json')
    backup.write_bytes(vault.value_path.read_bytes());backup.chmod(0o600)
    enabled.append(['ac_config','devLed']);vault.save(cfg)
assert ['ac_config','devLed'] in vault.load()['enabledWrites']
print(json.dumps({'enabled':'ac_config/devLed'}))
'''
    saved=json.loads(run(client,'docker run --rm -i --network none --entrypoint python '
        '-v /volume1/docker/shinya-hub/config:/config shinya-hub:oct10-identity-v22 -',
        stdin=save.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=25))
    result={**saved,'routers':proof}
finally:client.close()
Path('test/_analysis/oct10-pages/led-readback.json').write_text(json.dumps(result,indent=2),encoding='utf8')
print(json.dumps(result))
