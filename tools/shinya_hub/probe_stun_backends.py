"""Inspect reusable local STUN services without emitting their credentials."""
import json
import os
from remote_transport import connect_nas
from eweb_hub_remote import run

client = connect_nas()
try:
    script = r'''
import json,subprocess
names=subprocess.check_output(['docker','ps','--format','{{.Names}}']).decode().splitlines()
out=[]
for name in names:
    if name not in ('labprobe-hub','labprobe-hub-be50'):continue
    item=json.loads(subprocess.check_output(['docker','inspect',name]))[0]
    env={v.split('=',1)[0]:v.split('=',1)[1] for v in item['Config'].get('Env',[]) if '=' in v}
    port=int(env.get('PORT','58443'))
    # Resolve credentials inside the existing container and keep them on NAS.
    code="""import hub,json,requests
session=requests.Session();session.trust_env=False
headers={'Authorization':'Bearer '+hub.get_app_token()}
row={}
for path in ('/api/stun','/api/router/beta-upgrade'):
    try:
        response=session.get('http://127.0.0.1:'+str(hub.PORT)+path,headers=headers,timeout=10)
        value=response.json()
        row[path]={'status':response.status_code,'ok':value.get('ok'),'keys':list(value),
                    'rules':len(value.get('rules',[])), 'agentOnline':value.get('agentOnline'),
                    'data':value.get('data') if 'beta' in path else None}
    except Exception as error:row[path]={'error':type(error).__name__}
print(json.dumps(row))"""
    result=subprocess.run(['docker','exec',name,'python','-c',code],capture_output=True)
    if result.returncode:
        row={'probeExit':result.returncode,'errorType':result.stderr.decode(errors='replace').splitlines()[-1].split(':')[0],
             'workingDir':item['Config'].get('WorkingDir')}
    else:
        try:row=json.loads(result.stdout.decode().splitlines()[-1])
        except Exception as error:row={'error':type(error).__name__}
    row.update(name=name,port=port)
    out.append(row)
print(json.dumps(out))
'''
    print(run(client, 'python3 -', stdin=script.encode(), sudo_password=os.environ['SHINYA_NAS_PASSWORD'], timeout=30).decode())
finally:
    client.close()
