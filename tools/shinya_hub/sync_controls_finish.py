"""Sync only the reviewed production files, with local and NAS source guards."""
import hashlib,json,os
from pathlib import Path
from remote_transport import connect_nas,run
ROOT=Path('test/_analysis/oct12-controls-finish')
before=ROOT/'local-before';manifest=json.loads((before/'manifest.json').read_text())
target=Path('D:/Github/ShinyaHub/shinya_hub')
c=connect_nas();values={}
try:
    for name in manifest:
        live=run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/'+name,sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
        persisted=run(c,'cat /volume1/docker/shinya-hub/src/shinya_hub/'+name)
        assert live==persisted,'NAS source differs: '+name
        values[name]=live
finally:c.close()
for name,expected in manifest.items():
    p=target/name
    actual=hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None
    assert actual==expected,'Concurrent local edit: '+name
for name,value in values.items():(target/name).write_bytes(value)
print('Reviewed production source synced:',', '.join(values))
