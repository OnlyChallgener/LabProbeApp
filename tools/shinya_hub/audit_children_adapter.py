"""Read current child guard contracts and data without changing router rules."""
import concurrent.futures
import hashlib
import json
import os
import shlex
import time
from pathlib import Path
from remote_transport import connect_nas, connect_router, run

ROOT = Path('test/_analysis/oct11-children')

def router(family):
    client = connect_router(family)
    try:
        expected = {'be72':'G1TD8RX039025','be50':'G1TQ88T702111'}[family]
        identity = json.loads(run(client,'dev_sta get -m sysinfo'))
        assert identity['serial_num'] == expected
        result = {'sn':expected,'observedAt':time.time()}
        for key, command in {
            'config':'uci -q export child_guard',
            'native':'dev_config get -m child_guard',
            'users':'dev_sta get -m user_list',
            'info':'cat /proc/net/sniffer_info',
            'objects':"ubus -t 3 -v list 'sniffer*'",
            'firmware':'cat /usr/local/lua/dev_config/child_guard.lua',
            'block':'cat /usr/local/lua/dev_sta/child_block.lua',
            'pause':'cat /usr/local/lua/dev_sta/child_pause.lua',
            'minute':'cat /tmp/labprobe_minute_store.json',
        }.items():
            try:
                value=run(client,command,timeout=15).decode('utf8',errors='replace')
                result[key]=json.loads(value) if key in {'native','users','minute'} else value
            except Exception as error:
                result[key]={'error':type(error).__name__}
        (ROOT/(family+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
        rows=result['users'].get('list',[]) if isinstance(result['users'],dict) else []
        target=[r for r in rows if r.get('ip')=='192.168.5.201' or r.get('ipAddress')=='192.168.5.201']
        minute=result.get('minute',{})
        print(json.dumps({'family':family,'target':target,'minuteKeys':list(minute),
            'lastSample':minute.get('lastSample'),'sizes':{k:len(v) for k,v in minute.items() if isinstance(v,dict)},
            'config':result['config'],'objects':result['objects']},ensure_ascii=False),flush=True)
    finally:client.close()

NAS = r'''
import hashlib,json,sqlite3
from pathlib import Path
p=Path('/app/shinya_hub/api.py');text=p.read_text()
out={'apiSha256':hashlib.sha256(p.read_bytes()).hexdigest(), 'appReportIsSample':"# 模拟" in text or "sample_apps" in text}
for term in ('getStaAppReport','getAppTimePlans','getProtectStaListPage'):
 at=text.find("if resource == 'netguard/"+('onlineReport/' if term=='getStaAppReport' else 'staGuard/')+term+"'")
 out[term]=text[at:at+1700] if at>=0 else 'not found'
db=sqlite3.connect('file:/data/shinya.sqlite?mode=ro',uri=True);db.row_factory=sqlite3.Row
out['tables']=[r[0] for r in db.execute("select name from sqlite_master where type='table'")]
out['schema']={t:[dict(r) for r in db.execute('pragma table_info('+t+')')] for t in ('devices','snapshot','snapshots')}
print(json.dumps(out,ensure_ascii=False))
'''

def nas():
    client=connect_nas()
    try:
        value=json.loads(run(client,'docker exec -i shinya-hub python -',stdin=NAS.encode(),
            sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=30))
        (ROOT/'nas.json').write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps(value,ensure_ascii=False),flush=True)
    finally:client.close()

def main():
    ROOT.mkdir(parents=True,exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures=[pool.submit(router,f) for f in ('be72','be50')]+[pool.submit(nas)]
        for future in concurrent.futures.as_completed(futures):
            try:future.result()
            except Exception as e:print(json.dumps({'error':type(e).__name__,'detail':str(e)[:160]}),flush=True)

if __name__=='__main__':main()
