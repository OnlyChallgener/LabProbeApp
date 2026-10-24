"""Observe public reverse-forward destinations, never read SSH keys or passwords."""
import json,os,re,sys,time
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run
from remote_transport import connect_router

def main():
    family=sys.argv[1] if len(sys.argv)>1 else 'be72';label=sys.argv[2] if len(sys.argv)>2 else 'current'
    prefix='SHINYA_'+family.upper()
    c=connect_router(family)
    try:
        text=run(c,"sh -c 'ps w | grep -E \"[s]sh -R|[s]sh -vR\"; cat /tmp/sshtunnel/sshtunnel_cmd 2>/dev/null || true'").decode()
        mappings=[]
        for match in re.finditer(r'ssh\s+(?:-v)?-R\s+(\d+):([0-9.]+):(\d+)\b([^\n]*)',text):
            port,target,target_port,tail=match.groups()
            endpoint=re.search(r'\S+@([0-9.]+)\s+-p\s+(\d+)',tail)
            obj={'remoteListenPort':int(port),'targetIp':target,'targetPort':int(target_port)}
            if endpoint:obj.update({'relayHost':endpoint[1],'relaySshPort':int(endpoint[2])})
            if obj not in mappings:mappings.append(obj)
        result={'family':family,'capturedAt':time.time(),'label':label,'reverseForwards':mappings,
                'officialStatus':json.loads(run(c,"ubus -t 3 call shinya_mqlink status '{}'"))['status']}
        out=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/cloud-sync'
        (out/(family+'-reverse-'+label+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result),flush=True)
    finally:c.close()

if __name__=='__main__':main()
