"""Compile and test overlay against a fresh isolated NAS package copy."""
import base64
import json
import sys
import time
from pathlib import Path

sys.path.insert(0,'D:/Github/labprobe-hub/.tmp')
import zerotier_access as remote

root=Path(__file__).resolve().parent
files={path.name:base64.b64encode(path.read_bytes()).decode() for path in (root/'candidate').glob('*.py')}
for path in root.glob('test_*.py'):files[path.name]=base64.b64encode(path.read_bytes()).decode()
script='''import base64,json,pathlib,shutil,subprocess,sys
p=pathlib.Path('/data/notify-p1-check-'+str(ID))
shutil.copytree('/opt/shinya-hub/shinya_hub',p/'shinya_hub',ignore=shutil.ignore_patterns('__pycache__'))
for name,value in FILES.items():
 target=p/name if name.startswith('test_') else p/'shinya_hub'/name
 data=base64.b64decode(value);compile(data,name,'exec');target.write_bytes(data)
result=subprocess.run([sys.executable,str(p/'test_new.py')],cwd=p,capture_output=True,text=True)
print(json.dumps({'stage':str(p),'code':result.returncode,'stdout':result.stdout,'stderr':result.stderr}))
'''.replace('ID',str(int(time.time()))).replace('FILES',repr(files))
client=remote.nas()
try:
    result=remote.run(client,'docker exec -i shinya-hub python -',stdin=script.encode(),
                      sudo_password=remote.NAS_PASS,timeout=45)
    (root/'preflight.json').write_bytes(result)
    data=json.loads(result)
    print(data['stderr'])
    assert data['code']==0
finally:client.close()
