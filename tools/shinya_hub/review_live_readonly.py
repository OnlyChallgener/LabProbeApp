"""Read only owned services, native identity/capabilities, and display metadata."""
import json,os,sys,time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import run
from remote_transport import connect_nas,connect_router

OUT=Path(__file__).resolve().parents[2]/'test/_analysis/oct09-feature-review'

def router(family):
    result={'family':family,'capturedAt':time.time()}
    try:
        client=connect_router(family)
        try:
            native=json.loads(run(client,'dev_sta get -m sysinfo'))
            expected={'be72':'G1TD8RX039025','be50':'G1TQ88T702111'}[family]
            result['identity']={k:native.get(k) for k in ('serial_num','dev_model','software_version','hardware_version','sys_version','model') if k in native}
            if native.get('serial_num')!=expected:raise ValueError('Wrong router identity')
            result['officialCloud']=json.loads(run(client,"ubus -t 3 call shinya_mqlink status '{}'"))
            result['agentState']=run(client,"sh -c 'test -s /tmp/shinya-agent/latest.json && wc -c /tmp/shinya-agent/latest.json; ps w | grep -E \"[s]hinya-agent|[m]osquitto_pub\"'").decode()
            result['capabilities']={}
            for namespace,module in [('dev_cap','gateway'),('dev_cap','eweb'),('dev_config','mesh'),('dev_sta','uu_acc'),('dev_sta','rcgame')]:
                try:
                    raw=run(client,namespace+' get -m '+module,timeout=6)
                    value=json.loads(raw)
                    entry={'jsonType':type(value).__name__}
                    if isinstance(value,dict):
                        entry['fields']=sorted(value)
                        entry['flags']={k:v for k,v in value.items() if isinstance(v,(bool,int,float)) or
                                        (isinstance(v,str) and v in ('0','1','true','false','supported','unsupported','enable','disable'))}
                    result['capabilities'][namespace+'.'+module]=entry
                except Exception as exc:result['capabilities'][namespace+'.'+module]={'error':type(exc).__name__}
        finally:client.close()
    except Exception as exc:result['error']=type(exc).__name__
    return result

NAS_PROBE=r'''
import json,time,socket,ssl
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceRegistry
from shinya_hub.projection import station_snapshots
c=ConfigVault(Path('/config')).load();s=ProjectStore(Path('/data/shinya.sqlite'))
out={'enabledWrites':c.get('enabledWrites',[]),'credentialMode':'APP token hashes', 'devices':[], 'projects':[]}
for d in c['devices']:
    key=(d['tenant'],d['project'],d['sn'])
    with s.connect() as db:
        p=db.execute('select observed,sequence from push_transport where tenant=? and project=? and sn=?',key).fetchone()
    out['devices'].append({'family':d['family'],'sn':d['sn'],'pushAge':round(time.time()-p['observed'],2) if p else None,'sequence':p['sequence'] if p else None})
tenants={d['tenant'] for d in c['devices']}
for tenant in tenants:
    for p in s.projects(tenant):
        rows=station_snapshots(s,tenant,p)
        chosen=[{k:r.get(k) for k in ('mac','remark','hostName','hostname','type','typeLevel2','manufacturer','model','state','connectType','apSn')} for r in rows
                if any(word in str(r).lower() for word in ('fnos','trim-','飞牛','ugreen','绿联'))]
        out['projects'].append({'id':p['projectId'],'name':p.get('projectName') or p.get('name'),'selectedStations':chosen})
ports=[]
for port in (58543,58544,443,8443):
    entry={'port':port}
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=1) as raw:
            raw.settimeout(1)
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);context.check_hostname=False;context.verify_mode=ssl.CERT_NONE
            # Diagnostic inspection only; no credentials or app requests are sent.
            with context.wrap_socket(raw,server_hostname='localhost') as tls:entry['tls']=tls.version()
    except OSError as exc:entry['tlsError']=type(exc).__name__
    ports.append(entry)
out['tlsPorts']=ports
print(json.dumps(out,ensure_ascii=False))
'''

def nas():
    out={'capturedAt':time.time()}
    try:
        client=connect_nas();pw=os.environ['SHINYA_NAS_PASSWORD']
        try:
            out['services']=run(client,"docker ps --format '{{.Names}} {{.Image}} {{.Ports}}'",sudo_password=pw).decode()
            out['listeners']=run(client,"sh -c 'ss -lnt 2>/dev/null || netstat -lnt 2>/dev/null'",sudo_password=pw).decode()
            out['hub']=json.loads(run(client,'docker exec -i shinya-hub python -',stdin=NAS_PROBE.encode(),sudo_password=pw,timeout=25))
        finally:client.close()
    except Exception as exc:out['error']=type(exc).__name__
    return out

def main():
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures=[pool.submit(router,'be72'),pool.submit(router,'be50'),pool.submit(nas)]
        values=[f.result() for f in futures]
    out={'capturedAt':time.time(),'routers':values[:2],'nas':values[2]}
    OUT.mkdir(exist_ok=True,parents=True)
    (OUT/'live-readonly.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(out,ensure_ascii=False))

if __name__=='__main__':main()
