"""Read-only native statistics shape audit on the authorized NAS."""
import json, os, sys
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect, run

PROBE = r'''
import json
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.devices import DeviceDriver
from shinya_hub.commands import UFCommand
c=ConfigVault(Path('/config')).load();out=[]
for d in c['devices']:
    driver=DeviceDriver(d['address'],d['password'],d['family'],expected_sn=d['sn'])
    row={'family':d['family']}
    for module in ('overview','radioInfo','radio_channel','interface_info'):
        try:row[module]=driver.execute(UFCommand('dev_sta','get',module,None))
        except Exception as e:row[module]={'error':type(e).__name__}
    out.append(row)
print(json.dumps(out,ensure_ascii=False))
'''
c=connect(os.environ['SHINYA_NAS_HOST'],int(os.environ['SHINYA_NAS_PORT']),os.environ['SHINYA_NAS_USER'],os.environ['SHINYA_NAS_PASSWORD'])
try:
    out=json.loads(run(c,'docker exec -i shinya-hub python -',stdin=PROBE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=50))
    target=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/statistics-native.json'
    target.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(out,ensure_ascii=False))
finally:c.close()
