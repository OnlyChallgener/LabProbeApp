"""Deploy the verified game controls as a guarded overlay on the running HUB."""
import hashlib,os,shlex
from pathlib import Path
import deploy_device_controls as deploy
from remote_transport import connect_nas,run

OUT=Path('test/_analysis/oct13-official-game/game-controls-api')
NAMES=('api.py','devices.py','game_acceleration.py')

def main():
 global OUT
 revision=hashlib.sha256(b''.join(Path('D:/Github/ShinyaHub/shinya_hub',name).read_bytes() for name in NAMES)).hexdigest()[:12]
 OUT=OUT/revision
 baseline=OUT/'baseline';files=OUT/'overlay';baseline.mkdir(parents=True,exist_ok=True);files.mkdir(exist_ok=True)
 c=connect_nas()
 try:
  for name in NAMES:
   live=run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/'+name,sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=20)
   source=run(c,'cat /volume1/docker/shinya-hub/src/shinya_hub/'+name,sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=20)
   for p,value in [(baseline/name,live),(baseline/('source-'+name),source)]:
    if p.exists():assert p.read_bytes()==value,'Concurrent change: '+p.name
    else:p.write_bytes(value)
   (files/name).write_bytes(Path('D:/Github/ShinyaHub/shinya_hub',name).read_bytes())
 finally:c.close()
 deploy.FILES=files;deploy.BASELINE=baseline;deploy.RESULT=OUT/'result.json';deploy.main()

if __name__=='__main__':main()
