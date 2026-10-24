"""Deploy project-bound local STUN adapters; preserve previous encrypted config."""
import importlib.util
import json
import os
import sys
from pathlib import Path
from remote_transport import connect_nas
from eweb_hub_remote import run

client=connect_nas()
try:
    script=r'''
import subprocess,json,os,time
from pathlib import Path
credentials={}
for sn,name in [('G1TD8RX039025','labprobe-hub'),('G1TQ88T702111','labprobe-hub-be50')]:
    code="import hub,json;print(json.dumps({'token':hub.get_app_token(),'port':hub.PORT}))"
    result=subprocess.check_output(['docker','exec',name,'python','-c',code]).decode().splitlines()[-1]
    credentials[sn]=json.loads(result)
code="""import json,sys
from pathlib import Path
from shinya_hub.vault import ConfigVault
from Crypto.Cipher import AES
vault=ConfigVault(Path('/config'));settings=vault.load();credentials=json.load(sys.stdin)
bindings=[]
for d in settings['devices']:
    if d['sn'] in credentials:
        value=credentials[d['sn']]
        bindings.append({'tenant':d['tenant'],'project':d['project'],'sn':d['sn'],'port':value['port'],'token':value['token']})
assert len(bindings)==2
settings['stunBindings']=bindings
# Keep the existing APP login available until its three-field login ships.
settings['accountLoginEnabled']=False
cipher=AES.new(vault.key_path.read_bytes(),AES.MODE_GCM)
cipher.update(b'shinya-hub-configuration-v1')
encrypted,tag=cipher.encrypt_and_digest(json.dumps(settings,ensure_ascii=False).encode('utf-8'))
print(json.dumps({'version':1,'nonce':cipher.nonce.hex(),'tag':tag.hex(),'ciphertext':encrypted.hex()}))
"""
p=subprocess.run(['docker','exec','-i','shinya-hub','python','-c',code],input=json.dumps(credentials).encode(),capture_output=True,check=True)
envelope=json.loads(p.stdout.decode().splitlines()[-1])
root=Path('/volume1/docker/shinya-hub/config');target=root/'devices.enc.json'
stat=target.stat();backup=root/('devices-before-stun-'+str(int(time.time()))+'.enc.json')
backup.write_bytes(target.read_bytes());os.chmod(backup,0o600);os.chown(backup,stat.st_uid,stat.st_gid)
temporary=root/'devices-stun-staged.enc.json'
temporary.write_text(json.dumps(envelope));os.chmod(temporary,0o600);os.chown(temporary,stat.st_uid,stat.st_gid);temporary.replace(target)
print(json.dumps({'bindings':2,'configBackup':str(backup)}))
'''
    print(run(client,'python3 -',stdin=script.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=35).decode(),flush=True)
finally:
    client.close()
spec=importlib.util.spec_from_file_location('stun_nas_update','D:/Github/ShinyaHub/scripts/update_nas.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.IMAGE='shinya-hub:oct09-stun-v16'
module.main()
