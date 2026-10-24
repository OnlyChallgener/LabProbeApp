"""Enter the NAS-held credential into a reviewed, blank test-login form.

The credential stays in memory and is never printed or written to a file.
"""
import os
import re
import shlex
import sys

from remote_transport import connect_nas
from eweb_hub_remote import run
from adb_feature_audit import adb

READ = r'''
import os,yaml
from pathlib import Path
c=yaml.safe_load(Path(os.environ.get('CONFIG_PATH','/app/config/config.yaml')).read_text()) or {}
value=os.environ.get('APP_TOKEN') or c.get('server',{}).get('app_token')
if not value or value=='change-app-token':raise RuntimeError('No configured credential')
print(value)
'''

if __name__ == '__main__':
    client = connect_nas()
    try:
        credential = run(client, 'docker exec -i labprobe-hub python -', stdin=READ.encode(),
                         sudo_password=os.environ['SHINYA_NAS_PASSWORD']).decode('utf-8').strip()
    finally:
        client.close()
    if not re.fullmatch(r'[A-Za-z0-9_.-]{8,200}', credential):
        raise RuntimeError('Credential requires a different input mechanism')
    ids = set(re.findall(r'mDisplayId=(\d+)', adb('shell','dumpsys','display'))) - {'0'}
    if len(ids) != 1:raise RuntimeError('Expected one visible app window')
    display = ids.pop()
    adb('shell','input','-d',display,'tap','500','307')
    adb('shell','input','-d',display,'keyevent','123',*(['67']*200))
    adb('shell','input','-d',display,'text','http://127.0.0.1:58543')
    adb('shell','input','-d',display,'tap','500','451')
    adb('shell','input','-d',display,'keyevent','123',*(['67']*200))
    adb('shell','input','-d',display,'text',shlex.quote(credential))
    print('Reviewed login fields filled; credential not exported')
