"""Capture installed adapter and bounded native wireless contracts without credentials."""
import json
import os
from pathlib import Path
from remote_transport import connect_nas, connect_router, run

OUT = Path('test/_analysis/oct12-signal-tools')
OUT.mkdir(parents=True, exist_ok=True)

def unwrap(value):
    for _ in range(6):
        if isinstance(value, dict) and 'rcode' in value and 'data' in value:
            value = value['data']
        elif isinstance(value, str):
            try: value = json.loads(value)
            except ValueError: break
        else: break
    return value

def clean(value):
    if isinstance(value, dict):
        return {k: ('[redacted]' if any(s in k.lower() for s in ('password', 'passwd', 'token', 'secret', 'keystr')) else clean(v))
                for k, v in value.items()}
    if isinstance(value, list): return [clean(v) for v in value]
    return value

nas = connect_nas()
try:
    src = OUT / 'baseline' / 'shinya_hub'
    src.mkdir(parents=True, exist_ok=True)
    for name in ('api', 'devices', 'runtime', 'commands', 'projection', 'wifi_schedule', 'wireless', 'hub_scan'):
        try:
            data = run(nas, 'sudo -S docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/'+name+'.py',
                       sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
            (src / (name+'.py')).write_bytes(data)
        except Exception as e: print(name, type(e).__name__)
finally: nas.close()

for family in ('be72', 'be50'):
    c = connect_router(family)
    samples = {}
    try:
        identity = unwrap(json.loads(run(c, 'dev_sta get -m sysinfo')))
        expected = {'be72': 'G1TD8RX039025', 'be50': 'G1TH3QC007631'}
        samples['identity'] = {k: identity.get(k) for k in ('serial_num', 'model', 'sys_time', 'time')}
        if family == 'be72' and identity.get('serial_num') != expected[family]:
            raise ValueError('Wrong BE72 endpoint')
        for driver, module in [('dev_sta','radio_channel'), ('ac_config','wireless'), ('ac_config','tmngt'),
                               ('dev_config','radio'), ('dev_config','wio'), ('dev_config','wio_time'), ('dev_sta','wio')]:
            try: samples[driver+'.'+module] = clean(unwrap(json.loads(run(c, driver+' get -m '+module, timeout=30))))
            except Exception as e: samples[driver+'.'+module] = {'error': type(e).__name__, 'message': str(e)[:300]}
        # Lua modules are firmware source contracts; no runtime restarts are needed.
        code_dir = OUT / 'firmware' / family
        code_dir.mkdir(parents=True, exist_ok=True)
        for driver, module in [('dev_config','wio'), ('dev_config','wio_time'), ('dev_config','radio'),
                               ('dev_sta','wio'), ('dev_sta','chanIntfc'), ('dev_sta','radio_channel'),
                               ('ac_config','wireless')]:
            try: (code_dir / (driver+'-'+module+'.lua')).write_bytes(run(c, 'cat /usr/local/lua/'+driver+'/'+module+'.lua'))
            except Exception: pass
    finally: c.close()
    (OUT / (family+'-native.json')).write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding='utf-8')
    print(family, json.dumps(samples['identity'], ensure_ascii=False), 'captured', len(samples)-1, 'modules')
