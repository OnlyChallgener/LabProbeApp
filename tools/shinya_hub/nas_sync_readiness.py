"""Read-only dependency inventory for the independent synchronization service."""
import json,os,sys
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run
PROBE=r'''
import importlib.util,json
print(json.dumps({m:bool(importlib.util.find_spec(m)) for m in ('paho','cryptography','requests')}))
'''
c=connect(os.environ['SHINYA_NAS_HOST'],int(os.environ['SHINYA_NAS_PORT']),os.environ['SHINYA_NAS_USER'],os.environ['SHINYA_NAS_PASSWORD'])
try:
    out={}
    for key,cmd in {
        'containers':"docker ps --format '{{.Names}} {{.Image}}'",
        'images':"docker images --format '{{.Repository}}:{{.Tag}}'",
        'listeners':"netstat -lnt 2>/dev/null | grep -E '5854[345]|5844[345]|1883|9001'",
    }.items():
        out[key]=run(c,cmd,sudo_password=os.environ['SHINYA_NAS_PASSWORD']).decode()
    out['python']=json.loads(run(c,'docker exec -i shinya-hub python -',stdin=PROBE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
    target=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/cloud-sync/nas-readiness.json'
    target.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(out,ensure_ascii=False))
finally:c.close()
