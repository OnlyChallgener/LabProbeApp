"""Read deployment prerequisites without emitting credentials or configuration."""
import json
import os
from remote_transport import connect_nas
from eweb_hub_remote import run

client=connect_nas()
try:
    pw=os.environ['SHINYA_NAS_PASSWORD']
    probe=r'''
import json,shutil,subprocess,socket
info=json.loads(subprocess.check_output(['docker','inspect','shinya-hub']))[0]
out={'name':info['Name'],'image':info['Config']['Image'],'command':info['Config']['Cmd'],
     'openssl':bool(shutil.which('openssl')),'httpsPortFree':False}
try:
    s=socket.socket();s.bind(('0.0.0.0',58544));s.close();out['httpsPortFree']=True
except OSError:pass
p=subprocess.run(['docker','image','inspect','nginx:alpine'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
out['nginxImageCached']=p.returncode==0
print(json.dumps(out))
'''
    print(run(client,'python3 -',stdin=probe.encode(),sudo_password=pw,timeout=20).decode())
finally:client.close()
