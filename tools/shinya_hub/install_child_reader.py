import json,sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from remote_transport import connect_router,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write

def install(family):
    c=connect_router(family)
    try:
        expected={'be72':'G1TD8RX039025','be50':'G1TQ88T702111'}[family]
        assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==expected
        write(c,'/usr/local/lua/dev_sta/shinya_child.lua',Path('tools/shinya_hub/router_sync/shinya_child.lua').read_bytes())
        write(c,'/usr/local/lua/dev_config/shinya_child_control.lua',Path('tools/shinya_hub/router_sync/shinya_child_control.lua').read_bytes())
        write(c,'/usr/local/lua/shinya_child_delayed.lua',Path('tools/shinya_hub/router_sync/shinya_child_delayed.lua').read_bytes())
        result=json.loads(run(c,"dev_sta get -m shinya_child '{\"action\":\"config\"}'",timeout=12))
        usage=json.loads(run(c,"dev_sta get -m shinya_child '{\"action\":\"usage\",\"date\":\"2026-10-11\"}'",timeout=15))
        Path('test/_analysis/oct11-children/'+family+'-child-config.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps({'family':family,'users':len(result.get('users',[])), 'sampleAt':usage.get('generatedAt'),'appRows':len(usage.get('appMinutes',[]))}),flush=True)
    finally:c.close()

if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=2) as p:list(p.map(install,('be72','be50')))
