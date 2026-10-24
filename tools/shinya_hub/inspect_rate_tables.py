"""Read both router station tables for the upload-rate batch, no writes."""
import json,time
from pathlib import Path
from remote_transport import connect_router,run as raw_run
def run(*a,**k):
 v=raw_run(*a,**k);return v.decode("utf8","replace") if isinstance(v,bytes) else v
out={}
for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
 c=connect_router(family)
 try:
  identity=json.loads(run(c,'dev_sta get -m sysinfo'));assert identity['serial_num']==sn
  rows=json.loads(run(c,"dev_sta get -m user_list '{\"devType\":\"all\",\"dataType\":\"timely\"}'"))
  interfaces=run(c,'ls /sys/class/net').split()
  candidates=[i for i in interfaces if __import__('re').fullmatch(r'(?:rai?|ath|wlan)\d+',i)]
  tables={}
  for i in candidates:
   try:tables[i]=run(c,'timeout -t 2 wlanconfig '+i+' list show_all_stainfo',timeout=5)
   except Exception:tables[i]=''
  out[family]={'sn':sn,'observedAt':time.time(),'users':rows,'tables':tables,
   'agentHash':run(c,'sha256sum /etc/shinya-agent/collector.lua').strip()}
  print(json.dumps({'family':family,'tableSizes':{i:len(v) for i,v in tables.items()}}),flush=True)
 finally:c.close()
p=Path('test/_analysis/oct10-pages/rate-tables.json');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(out,indent=2),encoding='utf8')
