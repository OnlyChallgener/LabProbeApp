"""Configure authorized optional channels and deploy the isolated HUB batch."""
import importlib.util
import json
import os
import sys
from pathlib import Path
from remote_transport import connect_nas, run

READ=r'''
import json,os
from pathlib import Path
from assistant.security import decrypt_secret_with_migration
paths=[Path(os.environ.get('DATA_DIR','/app/data'))/'official_child_guard_accounts.json',Path('/data/official_child_guard_accounts.json')]
p=next((p for p in paths if p.is_file()),None)
if p is None:raise RuntimeError('Existing authorized binding unavailable')
print(json.dumps([json.loads(decrypt_secret_with_migration(v)[0]) for v in json.loads(p.read_text()).values()]))
'''
WRITE=r'''
import json,sys,time,shutil
from pathlib import Path
from shinya_hub.vault import ConfigVault
v=ConfigVault(Path('/config'));c=v.load();incoming=json.loads(sys.stdin.readline())
rows=[]
for d in c['devices']:
 matches=[b for b in incoming['bindings'] if str(b.get('serial'))==str(d['sn'])]
 if len(matches)!=1:raise RuntimeError('Optional binding identity is not unique')
 b=matches[0]
 rows.append({'tenant':d['tenant'],'project':d['project'],'sn':d['sn'],
  'officialProjectId':b['projectId'],'token':b['token'],
  'account':incoming['account'],'password':incoming['password']})
backup=v.value_path.with_name('devices.enc.pre-channels-'+str(int(time.time()))+'.json')
shutil.copy2(v.value_path,backup)
c['officialBindings']=rows;v.save(c)
print(json.dumps({'configured':len(rows),'encryptedBackup':backup.name}))
'''

def main():
    password=os.environ['SHINYA_NAS_PASSWORD'];client=connect_nas()
    try:
        bindings=[]
        for name in ('labprobe-hub','labprobe-hub-be50'):
            value=json.loads(run(client,'docker exec -i '+name+' python -',stdin=READ.encode(),sudo_password=password,timeout=25))
            bindings.extend(value)
        payload={'bindings':bindings,'account':os.environ['SHINYA_OFFICIAL_ACCOUNT'],
                 'password':os.environ['SHINYA_OFFICIAL_PASSWORD']}
        # Program and credentials travel only in SSH stdin. No plaintext file,
        # shell argument, tool output or log stores the official account secret.
        wrapper='import sys; exec('+repr(WRITE)+')'
        import shlex
        result=run(client,'docker run --rm -i --network none --entrypoint python '
            '-v /volume1/docker/shinya-hub/config:/config shinya-hub:oct10-cards-v25 '
            '-c '+shlex.quote(wrapper),stdin=(json.dumps(payload)+'\n').encode(),
            sudo_password=password,timeout=30)
        print(result.strip(),flush=True)
    finally:client.close()
    source=Path('D:/Github/ShinyaHub/scripts/update_nas.py');sys.path.insert(0,str(source.parent))
    spec=importlib.util.spec_from_file_location('priority_channels_deploy',source)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    module.IMAGE='shinya-hub:oct10-channels-v26';module.main()

if __name__=='__main__':main()
