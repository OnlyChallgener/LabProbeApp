"""One-shot native collection audit; report schemas and transport support only."""
import json,os,sys,time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run,write

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'test/_analysis/oct08-shinya-hub/cloud-sync'
SN={'be72':'G1TD8RX039025','be50':'G1TQ88T702111'}

def probe(family):
    prefix='SHINYA_'+family.upper()
    client=connect(os.environ[prefix+'_HOST'],int(os.environ[prefix+'_PORT']),
                   os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'])
    try:
        write(client,'/tmp/shinya-collector-audit.lua',(ROOT/'tools/shinya_hub/router_sync/collector.lua').read_bytes())
        write(client,'/tmp/shinya-collector-audit.json',json.dumps({'sn':SN[family],'once':True}).encode(),mode='600')
        start=time.time()
        raw=run(client,'lua /tmp/shinya-collector-audit.lua /tmp/shinya-collector-audit.json',timeout=55)
        if not raw.strip():
            result={'family':family,'emptyCollector':True,'diagnostics':{}}
            for label,command in {'timeoutUsage':'timeout --help','native':'dev_sta get -m sysinfo',
                                  'tools':'command -v lua mosquitto_pub mosquitto_sub timeout dev_sta'}.items():
                _,out,err=client.exec_command(command,timeout=15)
                data=out.read();error=err.read();code=out.channel.recv_exit_status()
                if label=='native':
                    try:
                        obj=json.loads(data)
                        shape={'keys':sorted(obj),'dataType':type(obj.get('data')).__name__}
                        if isinstance(obj.get('data'),str):shape['innerKeys']=sorted(json.loads(obj['data']))
                    except Exception:shape={'bytes':len(data)}
                else:shape=data.decode('utf-8','replace')[:1500]
                result['diagnostics'][label]={'exit':code,'shape':shape,'error':error.decode('utf-8','replace')[:1500]}
            return result
        frame=json.loads(raw)
        schemas={r['module']:sorted(r['value']) for r in frame['resources']}
        result={'family':family,'sn':frame['sn'],'seconds':round(time.time()-start,2),
                'payloadBytes':len(raw),'resourceSchemas':schemas,
                'stations':next((len(r['value'].get('list',[])) for r in frame['resources'] if r['module']=='user_list'),None)}
        result['configSupport']=run(client,r"strings /usr/bin/mosquitto_pub 2>/dev/null | grep -E 'XDG_CONFIG_HOME|mosquitto_pub$|\.config|mosquitto_pub -' | head -n 8").decode()
        result['procd']=run(client,'test -f /etc/rc.common && command -v procd').decode().strip()
        result['radioLogPublic']=run(client,r"uci -q show radioLog | grep -E '\.(rlogenable|gatherCyc|uploadCyc)='").decode().strip()
        result['officialStatus']=json.loads(run(client,"ubus -t 3 call shinya_mqlink status '{}'"))['status']
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/(family+'-collector-schema.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        return result
    finally:client.close()

if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=2) as pool:
        for result in pool.map(probe,SN):print(json.dumps(result,ensure_ascii=False),flush=True)
