"""Read owned router game/environment modules and sanitized metrics."""
import json
from pathlib import Path
from remote_transport import connect_router, run

OUT=Path('test/_analysis/oct12-game-environment')
OUT.mkdir(parents=True,exist_ok=True)

def clean(value):
    if isinstance(value,dict):
        return {k:('[redacted]' if any(x in k.lower() for x in ('passwd','password','token','secret','keystr')) else clean(v)) for k,v in value.items()}
    if isinstance(value,list):return [clean(v) for v in value]
    return value

def main():
    for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
        c=connect_router(family)
        result={}
        try:
            assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==sn
            names=run(c,'ls /usr/local/lua/dev_sta').decode().split()
            chosen=[n for n in names if any(x in n.lower() for x in ('game','radio','diag','intfc','ping','qoe','overview'))]
            print(family,'modules',chosen,flush=True)
            dest=OUT/family;dest.mkdir(exist_ok=True)
            for name in chosen:
                if not all(ch.isalnum() or ch in '._-' for ch in name):continue
                (dest/name).write_bytes(run(c,'cat /usr/local/lua/dev_sta/'+name))
            for module in ('overview','radioInfo','radio_channel','chanIntfc','user_list','ipinfo','port_status'):
                try:
                    result[module]=clean(json.loads(run(c,'dev_sta get -m '+module,timeout=30)))
                    print(family,module,'keys',list(result[module])[:20],flush=True)
                except Exception as e:result[module]={'error':type(e).__name__}
        finally:c.close()
        (OUT/(family+'-metrics.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')

if __name__=='__main__':main()
