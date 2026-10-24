"""Deploy the selected-router game support read contract with guarded baselines."""
import hashlib,os,shlex
from pathlib import Path
import deploy_device_controls as deploy
from remote_transport import connect_nas,run
ROOT=Path('D:/Github/ShinyaHub/shinya_hub')
OUT=Path('test/_analysis/oct13-official-game/game-support/deployment')
NAMES=('api.py','game_support.py','resources/supported_games.json')
def main():
 out=OUT/hashlib.sha256(b''.join((ROOT/n).read_bytes() for n in NAMES)).hexdigest()[:12]
 baseline=out/'baseline';files=out/'overlay';baseline.mkdir(parents=True,exist_ok=True);files.mkdir(exist_ok=True)
 c=connect_nas()
 try:
  for name in NAMES:
   for prefix,target in [('', '/opt/shinya-hub/shinya_hub/'),('source-','/volume1/docker/shinya-hub/src/shinya_hub/')]:
    cmd='if test -f '+shlex.quote(target+name)+'; then cat '+shlex.quote(target+name)+'; else printf __ABSENT_FILE__; fi'
    cmd=('docker exec shinya-hub sh -c ' if not prefix else 'sh -c ')+shlex.quote(cmd)
    value=run(c,cmd,sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
    p=baseline/(prefix+name);p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists():assert p.read_bytes()==value,'Concurrent change '+name
    else:p.write_bytes(value)
   p=files/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes((ROOT/name).read_bytes())
 finally:c.close()
 deploy.FILES=files;deploy.BASELINE=baseline;deploy.RESULT=out/'result.json';deploy.main()
if __name__=='__main__':main()
