import ast,sys,os,json,hashlib
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub/scripts')
from deploy_to_nas import NAS_USER,NAS_PASS
sys.path.insert(0,str(Path(__file__).resolve().parent))
from remote_transport import connect_router,run
out=Path('test/_analysis/oct16-terminal-ipv6');out.mkdir(parents=True,exist_ok=True)
os.environ.update(SHINYA_NAS_HOST='36.157.252.49',SHINYA_NAS_PORT='6011',SHINYA_NAS_USER=NAS_USER,SHINYA_NAS_PASSWORD=NAS_PASS)
tree=ast.parse(Path('D:/Github/ShinyaHub/scripts/be72_ssh.py').read_text(encoding='utf-8'))
call=next(n.value for n in ast.walk(tree) if isinstance(n,ast.Expr) and isinstance(n.value,ast.Call) and isinstance(n.value.func,ast.Attribute) and n.value.func.attr=='connect')
settings={k.arg:ast.literal_eval(k.value) for k in call.keywords if k.arg in {'username','password'}}
os.environ.update(SHINYA_BE72_HOST='36.157.252.49',SHINYA_BE72_PORT='5905',SHINYA_BE72_USER=settings['username'],SHINYA_BE72_PASSWORD=settings['password'])
tree=ast.parse(Path('tools/shinya_hub/connect_be50.py').read_text(encoding='utf-8'))
call=next(n for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='connect')
values=[ast.literal_eval(n) for n in call.args]
os.environ.update(SHINYA_BE50_HOST=values[0],SHINYA_BE50_PORT=str(values[1]),SHINYA_BE50_USER=values[2],SHINYA_BE50_PASSWORD=values[3])
def main():
    for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
        c=connect_router(family)
        try:
            identity=json.loads(run(c,'dev_sta get -m sysinfo'))
            assert identity.get('serial_num')==sn,'Router identity mismatch'
            raw=run(c,'cat /usr/local/lua/dev_sta/shinya_exec.lua')
            (out/(family+'-shinya_exec.lua')).write_bytes(raw)
            probe=run(c,"ip -6 neigh show; ip -6 addr show",timeout=12)
            (out/(family+'-ipv6-preflight.txt')).write_bytes(probe)
            print(family,'source',hashlib.sha256(raw).hexdigest(),'probe-bytes',len(probe),flush=True)
        finally:c.close()
if __name__=='__main__':main()
