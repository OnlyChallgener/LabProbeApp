"""Read current owned services and bounded native state; never export secrets."""
import os,json,socket,concurrent.futures
from pathlib import Path
from remote_transport import connect_nas,connect_router,run
OUT=Path('test/_analysis/oct12-followup');OUT.mkdir(parents=True,exist_ok=True)
PROBE=r'''
import subprocess,json
print(subprocess.check_output(['ss','-lntp']).decode())
print(subprocess.check_output(['docker','ps','--format','{{.Names}} {{.Ports}}']).decode())
code="""import hub,requests,json
s=requests.Session();s.trust_env=False;s.headers['Authorization']='Bearer '+hub.get_app_token()
v=s.get('http://127.0.0.1:'+str(hub.PORT)+'/api/stun',timeout=15).json()
print(json.dumps({k:v.get(k) for k in ('rules','agentOnline')}))
"""
r=subprocess.run(['docker','exec','labprobe-hub','python','-c',code],capture_output=True)
print(r.stdout.decode());print('stunProbeExit',r.returncode)
'''
c=connect_nas()
try:
    raw=run(c,'python3 -',stdin=PROBE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=40).decode()
    # Only listener rows for services in this task are needed.
    lines=raw.splitlines();safe=[];rules=[]
    for line in lines:
        if line.startswith('{'):
            v=json.loads(line);rules=v.get('rules') or []
            safe.append(json.dumps({'agentOnline':v.get('agentOnline'),'rules':[{k:r.get(k) for k in ('id','name','enabled','targetIpv4','targetPort','listenPort','forwardMode','transportProtocol','actualState','nativeMappingState','firewallState','runtime','addresses','syncError')} for r in rules]}))
        elif any(p in line for p in ('2122','58443','58444','58543','58544','labprobe','shinya','stunProbeExit')):safe.append(line)
    (OUT/'stun-listeners.txt').write_text('\n'.join(safe),encoding='utf8')
    print('\n'.join(safe))
finally:c.close()
c=connect_router('be72')
try:
    print('BE72 sysinfo',json.loads(run(c,'dev_sta get -m sysinfo')).get('serial_num'))
    raw=run(c,"for p in 2122 58443 58444 58543 58544; do nc -z -w 2 192.168.5.46 $p && echo open:$p || echo closed:$p; done",timeout=15).decode();print(raw)
    (OUT/'nas-from-be72.txt').write_text(raw,encoding='utf8')
finally:c.close()
endpoints=[]
for r in rules:
    if r.get('targetIpv4')=='192.168.5.46' and r.get('targetPort') in (2122,58443,58444):
        endpoint=(r.get('runtime') or {}).get('publicEndpoint','')
        if endpoint:endpoints.append(endpoint)
def probe(endpoint):
    host,port=endpoint.rsplit(':',1)
    try:
        with socket.create_connection((host,int(port)),timeout=5) as sock:
            sock.settimeout(3)
            return {'endpoint':endpoint,'connected':True}
    except Exception as ex:return {'endpoint':endpoint,'connected':False,'error':type(ex).__name__}
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:result=list(pool.map(probe,endpoints))
(OUT/'public-probes.json').write_text(json.dumps(result,indent=2),encoding='utf8');print(json.dumps(result))
