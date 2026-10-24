"""Read current independent HUB source before a guarded child overlay."""
import hashlib,os
from pathlib import Path
from remote_transport import connect_nas,run

def main():
    directory=Path('test/_analysis/oct11-children')
    client=connect_nas()
    try:
        for name in ('api.py','runtime.py','projection.py','store.py','catalog.py','assets.py'):
            source=run(client,'cat /volume1/docker/shinya-hub/src/shinya_hub/'+name,timeout=15)
            live=run(client,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/'+name,
                     sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=15)
            assert source==live,'Source and installed package differ: '+name
            (directory/('nas-'+name)).write_bytes(live)
            (directory/('nas-source-'+name)).write_bytes(source)
            print(name,hashlib.sha256(source).hexdigest(),flush=True)
    finally:client.close()

if __name__=='__main__':main()
