"""Append only live local BSS records to the router's existing survey adapter."""
import hashlib,json,os,shlex,sys,time
from pathlib import Path
from remote_transport import connect_router,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write
OUT=Path('test/_analysis/oct12-local-scan');OUT.mkdir(exist_ok=True)
REMOTE='/usr/local/lua/dev_sta/shinya_exec.lua'
def main():
    data=Path('tools/shinya_hub/router_sync/shinya_exec.lua').read_bytes();proof=[]
    for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
        c=connect_router(family)
        try:
            assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==sn
            old=run(c,'cat '+REMOTE);(OUT/(family+'-before.lua')).write_bytes(old)
            pid_command="ps | sed -n '/rdpi/p' | sed '/sed /d'"
            pids=run(c,pid_command).decode().strip()
            temporary='/tmp/shinya-self-survey-'+str(int(time.time()))+'.lua'
            write(c,temporary,data,mode='644')
            run(c,'lua -e '+shlex.quote("assert(loadfile('"+temporary+"'))"))
            assert old==run(c,'cat '+REMOTE),'Concurrent firmware adapter change'
            try:
                run(c,'cp '+temporary+' '+REMOTE)
                assert data==run(c,'cat '+REMOTE)
                value=json.loads(run(c,"dev_sta get -m shinya_exec '{\"action\":\"survey\",\"bands\":[\"2.4G\",\"5G\"]}'",timeout=55))
                assert all(isinstance(value.get('bands',{}).get(b),list) for b in ('2.4G','5G'))
                local=[r for rows in value['bands'].values() for r in rows if r.get('isSelfSsid')]
                assert local and all(r.get('bssid') and r.get('channel') and r.get('signal')==0 for r in local)
                assert run(c,pid_command).decode().strip()==pids,'Recognition process changed'
                (OUT/(family+'-survey.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
                proof.append({'sn':sn,'localBssCount':len(local),'rows':[{k:r.get(k) for k in ('ssid','bssid','channel','iface')} for r in local],
                              'rdpiPidUnchanged':True,'sha256':hashlib.sha256(data).hexdigest()})
                print(json.dumps(proof[-1],ensure_ascii=False),flush=True)
            except Exception:
                write(c,REMOTE,old,mode='644');raise
            finally:run(c,'rm -f '+temporary)
        finally:c.close()
    (OUT/'proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
if __name__=='__main__':main()
