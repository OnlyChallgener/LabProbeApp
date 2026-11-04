"""Deploy only the tested P3 notification overlay with exact live-base preconditions."""
import base64
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, 'D:/Github/labprobe-hub/.tmp')
import zerotier_access as remote

root = Path(__file__).resolve().parent
preflight = json.loads((root / 'preflight.json').read_text())
assert preflight['code'] == 0
names = ('router_alias.py','projection.py','api.py')
payload = {}
for name in names:
    base = root / 'live-base' / name
    value = (root / 'candidate' / name).read_bytes()
    payload[name] = {'before': hashlib.sha256(base.read_bytes()).hexdigest() if base.exists() else None,
                     'value': base64.b64encode(value).decode(),
                     'after': hashlib.sha256(value).hexdigest()}

script = '''
import base64,hashlib,json,os,time
from pathlib import Path
live=Path('/opt/shinya-hub/shinya_hub')
backup=Path('/data/router-alias-backup-'+str(int(time.time())))
files=PAYLOAD
for name,record in files.items():
 target=live/name
 assert not target.is_symlink()
 before=hashlib.sha256(target.read_bytes()).hexdigest() if target.exists() else None
 assert before==record['before'],'Live source changed: '+name
 value=base64.b64decode(record['value']);compile(value,name,'exec')
 assert hashlib.sha256(value).hexdigest()==record['after']
backup.mkdir(mode=0o700)
for name,record in files.items():
 target=live/name
 if target.exists():(backup/name).write_bytes(target.read_bytes())
 temporary=target.with_name(name+'.notification-next')
 temporary.write_bytes(base64.b64decode(record['value']))
 os.replace(temporary,target)
result={'backup':str(backup),'files':{n:{k:v for k,v in r.items() if k!='value'} for n,r in files.items()}}
(backup/'manifest.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
'''.replace('PAYLOAD', repr(payload))

script = "import traceback\ntry:\n" + '\n'.join(' '+line for line in script.splitlines()) + "\nexcept Exception:\n traceback.print_exc(file=__import__('sys').stdout)\n print('{}')\n"
client = remote.nas()
try:
    result = remote.run(client, 'docker exec -i shinya-hub python -', stdin=script.encode(),
                        sudo_password=remote.NAS_PASS)
    print(result.decode() if isinstance(result,bytes) else result)
    deployment = json.loads(result)
    assert deployment.get('backup'), 'Deployment aborted before mutation'
    (root / 'deployment.json').write_text(json.dumps(deployment, indent=2))
    remote.run(client, 'docker restart shinya-hub', sudo_password=remote.NAS_PASS, timeout=45)
    check = '''
import json,time,urllib.request
for attempt in range(20):
 try:
  data=json.load(urllib.request.urlopen('http://127.0.0.1:58543/healthz',timeout=2))
  print(json.dumps(data));break
 except Exception:
  time.sleep(1)
else:raise RuntimeError('Health check failed')
'''
    try:
        health = remote.run(client, 'docker exec -i shinya-hub python -', stdin=check.encode(),
                            sudo_password=remote.NAS_PASS, timeout=35)
    except Exception:
        restore = '''
import os,json
from pathlib import Path
backup=Path(BACKUP);live=Path('/opt/shinya-hub/shinya_hub')
manifest=json.loads((backup/'manifest.json').read_text())
for name,record in manifest['files'].items():
 target=live/name
 if record['before'] is None:target.unlink()
 else:
  temporary=target.with_name(name+'.notification-restore')
  temporary.write_bytes((backup/name).read_bytes());os.replace(temporary,target)
'''.replace('BACKUP', repr(deployment['backup']))
        remote.run(client, 'docker exec -i shinya-hub python -', stdin=restore.encode(),
                   sudo_password=remote.NAS_PASS)
        remote.run(client, 'docker restart shinya-hub', sudo_password=remote.NAS_PASS, timeout=45)
        raise
    (root / 'health.json').write_bytes(health)
    print('Deployed notification scene settings; service healthy; backup ' + deployment['backup'])
finally:
    client.close()
