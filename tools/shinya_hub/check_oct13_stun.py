"""Read current STUN rules using the NAS-local existing service credentials."""
import os
from pathlib import Path
from remote_transport import connect_nas, run

PROBE = r'''
import hub,json,requests
s=requests.Session();s.trust_env=False
r=s.get('http://127.0.0.1:'+str(hub.PORT)+'/api/stun',headers={'Authorization':'Bearer '+hub.get_app_token()},timeout=12)
v=r.json()
fields={'id','name','serviceType','transportProtocol','targetType','targetIpv4','targetPort','enabled','status','state','publicIpv4','publicPort','publicAddress','lastPublicAddress','lastError','error','updatedAt','localPort','listenPort','addresses','runtime','lastSuccessAt','health'}
def clean(value):
 if isinstance(value,dict):return {k:clean(x) for k,x in value.items() if not any(t in k.lower() for t in ('token','secret','password','credential'))}
 if isinstance(value,list):return [clean(x) for x in value]
 return value
print(json.dumps({'http':r.status_code,'ok':v.get('ok'),'keys':list(v),'agentOnline':v.get('agentOnline'),'rules':[{k:clean(x) for k,x in row.items() if k in fields} for row in v.get('rules',[])]},ensure_ascii=False))
'''
client=connect_nas()
try:
    value=run(client,'docker exec -i labprobe-hub python -',stdin=PROBE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=20).decode()
    out=Path('test/_analysis/oct13-official-game/stun-live.json');out.write_text(value,encoding='utf8')
    print(value)
finally:client.close()
