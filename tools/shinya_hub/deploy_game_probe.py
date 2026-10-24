"""Install one read-only probe module after checking router identity."""
import hashlib,json,shlex,sys,time
from pathlib import Path
from remote_transport import connect_router,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write

OUT=Path('test/_analysis/oct12-game-environment')
REMOTE='/usr/local/lua/dev_sta/shinya_game.lua'
def main():
    data=Path('tools/shinya_hub/router_sync/shinya_game.lua').read_bytes()
    proof=[]
    for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
        c=connect_router(family)
        try:
            assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==sn
            old=run(c,'if test -f '+REMOTE+'; then cat '+REMOTE+'; else printf __ABSENT_FILE__; fi')
            (OUT/(family+'-probe-before.lua')).write_bytes(old)
            temporary='/tmp/shinya-game-'+str(int(time.time()))+'.lua'
            write(c,temporary,data,mode='644')
            run(c,'lua -e '+shlex.quote("assert(loadfile('"+temporary+"'))"))
            assert old==run(c,'if test -f '+REMOTE+'; then cat '+REMOTE+'; else printf __ABSENT_FILE__; fi')
            run(c,'cp '+temporary+' '+REMOTE)
            assert data==run(c,'cat '+REMOTE)
            value=json.loads(run(c,'dev_sta get -m shinya_game',timeout=40))
            assert value.get('source')=='firmware.shinya_game'
            (OUT/(family+'-probe.json')).write_text(json.dumps(value,indent=2),encoding='utf8')
            proof.append({'family':family,'sn':sn,'sha256':hashlib.sha256(data).hexdigest(),
                          'probes':[{k:r.get(k) for k in ('host','status','avg','lossRate','sent','received')} for r in value['rows']]})
            print(json.dumps(proof[-1]),flush=True)
        finally:c.close()
    (OUT/'probe-deployment.json').write_text(json.dumps(proof,indent=2),encoding='utf8')
if __name__=='__main__':main()
