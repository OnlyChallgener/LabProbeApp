import sys,json,time,hashlib,datetime
from pathlib import Path
from read_router_ipv6_sources import connect_router,run,out
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.station_ipv6 import observed_candidates

def unwrap(raw):
    value=json.loads(raw)
    for _ in range(5):
        if value.get('action')=='ipv6':return value
        data=value.get('data')
        if isinstance(data,str):value=json.loads(data)
        elif isinstance(data,dict):value=data
        else:break
    raise RuntimeError('Native IPv6 result shape not confirmed')

for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
    before=(out/(family+'-shinya_exec.lua')).read_bytes()
    candidate=(out/'staged'/(family+'-shinya_exec.lua')).read_bytes()
    c=connect_router(family);changed=False
    try:
        assert json.loads(run(c,'dev_sta get -m sysinfo')).get('serial_num')==sn
        live=run(c,'cat /usr/local/lua/dev_sta/shinya_exec.lua')
        assert hashlib.sha256(live).digest()==hashlib.sha256(before).digest(),'Concurrent native change'
        write(c,'/tmp/shinya_exec-ipv6-reviewed.lua',candidate)
        run(c,"lua -e 'assert(loadfile(\"/tmp/shinya_exec-ipv6-reviewed.lua\"))'")
        backup='/etc/shinya-agent/backups/shinya_exec-before-ipv6-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.lua'
        run(c,'mkdir -p /etc/shinya-agent/backups')
        write(c,backup,before)
        changed=True
        write(c,'/usr/local/lua/dev_sta/shinya_exec.lua',candidate)
        value=unwrap(run(c,"dev_sta get -m shinya_exec '{\"action\":\"ipv6\"}'",timeout=15))
        (out/(family+'-live-ipv6.json')).write_text(json.dumps(value,indent=2),encoding='utf-8')
        now=time.time();candidates=observed_candidates({'observedAt':now,'value':value},now)
        print(json.dumps({'router':family,'installed':True,'backup':backup,'lanInterfaces':value.get('lanInterfaces'),
              'qualifiedCandidates':len(candidates),'sourceSha256':hashlib.sha256(candidate).hexdigest()}),flush=True)
    except Exception:
        if changed:write(c,'/usr/local/lua/dev_sta/shinya_exec.lua',before)
        raise
    finally:c.close()
