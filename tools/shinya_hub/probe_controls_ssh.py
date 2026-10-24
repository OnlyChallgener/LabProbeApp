"""Read real router identity and control schemas; leave all settings unchanged."""
import json,time
from pathlib import Path
from remote_transport import connect_router,run
proof={'capturedAt':time.time(),'routers':{}}
for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
 result={};client=None
 try:
  client=connect_router(family)
  identity=json.loads(run(client,'dev_sta get -m sysinfo'))
  assert identity.get('serial_num')==sn,'Router identity mismatch'
  result['verifiedSerial']=sn
  for namespace,module in [('ac_config','devLed'),('ac_config','timeReboot'),('dev_sta','radio_channel')]:
   result[namespace+'.'+module]=json.loads(run(client,namespace+' get -m '+module))
  result['listeners']=run(client,'netstat -lnt').decode('utf-8','replace')
 except Exception as e:result['errorType']=type(e).__name__
 finally:
  if client is not None:client.close()
 proof['routers'][family]=result
 print(json.dumps({'family':family,'verifiedSerial':result.get('verifiedSerial'),'errorType':result.get('errorType'),'led':result.get('ac_config.devLed',{}).get('led_all')}),flush=True)
p=Path('test/_analysis/oct10-pages/controls-ssh-reconnected.json')
p.write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
