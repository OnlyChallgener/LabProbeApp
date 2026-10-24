"""Correct the original APP's MAC-list contract without changing router settings."""
import ast,hashlib,os
from pathlib import Path
from remote_transport import connect_nas,run
import deploy_device_controls as deploy

ROOT=Path('test/_analysis/oct13-official-game/only5g-state')

def function_slice(source,name):
 tree=ast.parse(source)
 node=next(v for v in tree.body if isinstance(v,ast.FunctionDef) and v.name==name)
 lines=source.splitlines(keepends=True)
 return node.lineno-1,node.end_lineno,lines

def main():
 local=Path('D:/Github/ShinyaHub/shinya_hub/station_controls.py').read_text(encoding='utf8')
 start,end,lines=function_slice(local,'only5g_rows');replacement=''.join(lines[start:end])
 c=connect_nas()
 try:
  live=run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/station_controls.py',sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
  source=run(c,'cat /volume1/docker/shinya-hub/src/shinya_hub/station_controls.py',sudo_password=os.environ['SHINYA_NAS_PASSWORD'])
  start,end,lines=function_slice(live.decode(),'only5g_rows')
  adapted=''.join(lines[:start])+replacement+'\n'+''.join(lines[end:])
  compile(adapted,'station_controls.py','exec')
  assert 'def delete_rate_by_name(' in adapted,'Unlimited-rate fix must be retained'
  tag=hashlib.sha256(adapted.encode()).hexdigest()[:12]
  out=ROOT/tag;baseline=out/'baseline';files=out/'overlay'
  baseline.mkdir(parents=True,exist_ok=True);files.mkdir(exist_ok=True)
  for name,value in [('station_controls.py',live),('source-station_controls.py',source)]:
   p=baseline/name
   if p.exists():assert p.read_bytes()==value,'Snapshot changed'
   else:p.write_bytes(value)
  (files/'station_controls.py').write_text(adapted,encoding='utf8')
 finally:c.close()
 deploy.FILES=files;deploy.BASELINE=baseline;deploy.RESULT=out/'result.json';deploy.main()

if __name__=='__main__':main()
