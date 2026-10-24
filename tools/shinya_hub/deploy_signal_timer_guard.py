"""Ship the timer guard against the just-installed module with hash protection."""
from pathlib import Path
import os,shutil
from remote_transport import connect_nas,run
import deploy_device_controls as deploy

root=Path('test/_analysis/oct12-signal-tools/timer-guard')
out=root/'deployment';base=root/'baseline'
out.mkdir(parents=True,exist_ok=True);base.mkdir(parents=True,exist_ok=True)
c=connect_nas()
try:
    pw=os.environ['SHINYA_NAS_PASSWORD']
    (base/'signal_tools.py').write_bytes(run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/signal_tools.py',sudo_password=pw))
    (base/'source-signal_tools.py').write_bytes(run(c,'cat /volume1/docker/shinya-hub/src/shinya_hub/signal_tools.py'))
finally:c.close()
shutil.copyfile('tools/shinya_hub/signal_tools_source.py',out/'signal_tools.py')
deploy.FILES=out;deploy.BASELINE=base;deploy.RESULT=root/'deployment-result.json';deploy.main()
