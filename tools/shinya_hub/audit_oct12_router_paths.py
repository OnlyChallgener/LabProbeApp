"""Owned router firewall, WEB sync capability and iPad event diagnostics."""
import json,os,concurrent.futures
from pathlib import Path
from remote_transport import connect_router,run
OUT=Path('test/_analysis/oct12-followup');OUT.mkdir(exist_ok=True)
def inspect(family):
 c=connect_router(family)
 try:
  sn=json.loads(run(c,'dev_sta get -m sysinfo')).get('serial_num')
  commands={'be72': [('events',"dev_sta get -m shinya_station -d '{\"action\":\"history\"}'"),('firewall','iptables -S'),('nat','iptables -t nat -S'),('fwconfig','dev_config get -m ip_firewall'),('portmap','dev_config get -m port_mapping')], 'be50':[]}[family]
  out={'family':family,'sn':sn}
  for name,command in commands:
   try:
    raw=run(c,command,timeout=20).decode()
    if name=='events':
     v=json.loads(raw);raw=json.dumps([r for r in v.get('events',[]) if r.get('mac','').lower()=='c6019a7d27ff'],indent=2)
    (OUT/(family+'-'+name+'.txt')).write_text(raw,encoding='utf8')
    out[name]=raw[:1000] if name!='events' else json.loads(raw)[-10:]
   except Exception as ex:out[name]={'error':type(ex).__name__}
  # Module inventory filenames only, never configuration credentials.
  raw=run(c,"find /usr/local/lua /usr/lib/lua -maxdepth 2 -type f -iname '*remark*' -o -iname '*sync*' -o -iname '*sta*'",timeout=15).decode()
  (OUT/(family+'-modules.txt')).write_text(raw,encoding='utf8');out['modules']=raw
  return out
 finally:c.close()
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 for result in pool.map(inspect,('be72','be50')):print(json.dumps(result,ensure_ascii=False),flush=True)
