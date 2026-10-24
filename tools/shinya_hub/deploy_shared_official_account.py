"""Deploy the shared-account module with source guards and image rollback."""
import os,shlex,shutil
from pathlib import Path
import deploy_device_controls as deploy
from remote_transport import connect_nas,run

root=Path('test/_analysis/oct12-shared-account')
deploy.FILES=root/'deployment';deploy.BASELINE=root/'baseline';deploy.RESULT=root/'deployment-result.json'
deploy.FILES.mkdir(parents=True,exist_ok=True);deploy.BASELINE.mkdir(parents=True,exist_ok=True)
shutil.copyfile('tools/shinya_hub/official_account_source.py',deploy.FILES/'official_account.py')
client=connect_nas()
try:
 for path,name,inside in [('/opt/shinya-hub/shinya_hub/official_account.py','official_account.py',True),('/volume1/docker/shinya-hub/src/shinya_hub/official_account.py','source-official_account.py',False)]:
  cmd='cat '+shlex.quote(path)
  data=run(client,'docker exec shinya-hub sh -c '+shlex.quote(cmd) if inside else cmd,sudo_password=os.environ['SHINYA_NAS_PASSWORD'] if inside else None)
  (deploy.BASELINE/name).write_bytes(data)
finally:client.close()
deploy.main()
