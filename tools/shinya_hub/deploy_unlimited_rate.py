"""Deploy only the missing deletion path; preserve unrelated live station code."""
import hashlib,os
from pathlib import Path
from remote_transport import connect_nas,run
import deploy_device_controls as deploy

ROOT=Path('test/_analysis/oct13-official-game/rate-unlimited')
def main():
 base=(ROOT/'station_controls.py').read_text(encoding='utf8')
 local=Path('D:/Github/ShinyaHub/shinya_hub/station_controls.py').read_text(encoding='utf8')
 helper=local[local.index('def delete_rate_by_name('):local.index('def change_rate(')]
 adapted=base.replace('def change_rate(',helper+'def change_rate(',1)
 anchor=" if command.action not in ('add','update','del'):raise ValueError('仅支持单个终端限速的增删和更新')\n"
 assert adapted.count(anchor)==1
 adapted=adapted.replace(anchor,anchor+" data=command.data if isinstance(command.data,dict) else {}\n names=data.get('rulename')\n if command.action=='del' and isinstance(names,list) and not isinstance(data.get('list'),list):\n  return delete_rate_by_name(registry,key,command,[str(name) for name in names],station_ips)\n")
 tag=hashlib.sha256(adapted.encode()).hexdigest()[:12];out=ROOT/tag;baseline=out/'baseline';files=out/'overlay';baseline.mkdir(parents=True,exist_ok=True);files.mkdir(exist_ok=True)
 c=connect_nas()
 try:
  live=run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/station_controls.py',sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
  assert live.decode().splitlines()==base.splitlines(),'Concurrent live change; preserve it'
  source=run(c,'cat /volume1/docker/shinya-hub/src/shinya_hub/station_controls.py',sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
  for name,value in [('station_controls.py',live),('source-station_controls.py',source)]:
   p=baseline/name
   if p.exists():assert p.read_bytes()==value
   else:p.write_bytes(value)
  (files/'station_controls.py').write_text(adapted,encoding='utf8')
 finally:c.close()
 deploy.FILES=files;deploy.BASELINE=baseline;deploy.RESULT=out/'result.json';deploy.main()

if __name__=='__main__':main()
