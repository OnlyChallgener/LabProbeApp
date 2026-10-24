import sys,json,hashlib,ast
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub/scripts')
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from deploy_to_nas import NAS_USER,NAS_PASS
from eweb_hub_remote import connect,run
out=Path('test/_analysis/oct16-terminal-ipv6');out.mkdir(parents=True,exist_ok=True)
c=connect('36.157.252.49',6011,NAS_USER,NAS_PASS)
try:
    for name in ('api.py','projection.py'):
        raw=run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/'+name,sudo_password=NAS_PASS)
        (out/('live-'+name)).write_bytes(raw)
        print(name,hashlib.sha256(raw).hexdigest())
    print('NAS-read-ready')
finally:c.close()
