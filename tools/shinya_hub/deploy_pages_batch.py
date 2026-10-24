"""Deploy the tested independent page adapters with encrypted MACC binding."""
import importlib.util,json,os,shlex,sys
from pathlib import Path
from remote_transport import connect_nas,run
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.vault import ConfigVault
WRITE=r'''
import json,sys,time,shutil
from pathlib import Path
from shinya_hub.vault import ConfigVault
v=ConfigVault(Path('/config'));c=v.load();incoming=json.loads(sys.stdin.readline())
expected={'G1TD8RX039025':'18850348','G1TQ88T702111':'17953001'}
for r in c['officialBindings']:
 if r['sn'] not in expected or str(r['officialProjectId'])!=expected[r['sn']]:raise RuntimeError('Unexpected official project mapping')
 if r.get('account')!=incoming['account']:raise RuntimeError('Account binding does not match')
 r.update(maccEnabled=True,maccAccount=incoming['account'],maccCookies=incoming['cookies'])
backup=v.value_path.with_name('devices.enc.pre-pages-'+str(int(time.time()))+'.json');shutil.copy2(v.value_path,backup);v.save(c)
print(json.dumps({'maccBindings':len(c['officialBindings']),'encryptedBackup':backup.name}))
'''
def main():
 cookies=ConfigVault(Path('test/_keys/shinya-macc-session')).load()['cookies']
 payload={'account':os.environ['SHINYA_OFFICIAL_ACCOUNT'],'cookies':cookies}
 c=connect_nas()
 try:
  answer=run(c,'docker run --rm -i --network none --entrypoint python -v /volume1/docker/shinya-hub/config:/config shinya-hub:oct10-channels-v26 -c '+shlex.quote('import sys;exec('+repr(WRITE)+')'),stdin=(json.dumps(payload)+'\n').encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=30)
  print(answer.decode().strip() if isinstance(answer,bytes) else answer.strip(),flush=True)
 finally:c.close()
 source=Path('D:/Github/ShinyaHub/scripts/update_nas.py');sys.path.insert(0,str(source.parent))
 spec=importlib.util.spec_from_file_location('pages_deploy',source);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 module.IMAGE='shinya-hub:oct10-pages-v27';module.main()
if __name__=='__main__':main()
