"""Inspect retained feature schemas on the user's verified BE72 eWeb endpoint."""
import json,os,sys,time
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.devices import DeviceDriver
from shinya_hub.commands import UFCommand
out={'capturedAt':time.time(),'transport':'authorized eWeb RPC','router':'BE72','features':{}}
def safe(value):
 if isinstance(value,dict):
  return {k:('[redacted]' if any(x in k.lower() for x in ('password','passwd','token','cookie','secret','psk','pwd','key')) else safe(v)) for k,v in value.items()}
 if isinstance(value,list):return [safe(v) for v in value]
 return value
try:
 d=DeviceDriver('http://111.23.167.101:12597',os.environ['SHINYA_BE72_PASSWORD'],'be72',expected_sn='G1TD8RX039025')
 native=d.execute(UFCommand('dev_sta','get','sysinfo',None))
 assert native.get('serial_num')=='G1TD8RX039025'
 out['verifiedSerial']=native['serial_num']
 for namespace,module,data in [('dev_cap','gateway',None),('dev_cap','eweb',None),('dev_sta','uu_acc',None),('dev_sta','rcgame',None),('ac_config','devLed',None),('ac_config','timeReboot',None),('ac_config','lowRate',None),('dev_config','radio',None),('dev_sta','radio_channel',None)]:
  start=time.monotonic()
  try:
   value=d.execute(UFCommand(namespace,'get',module,data))
   out['features'][namespace+'.'+module]={'elapsedMs':round((time.monotonic()-start)*1000),'response':safe(value)}
   print(json.dumps({'module':module,'fields':list(value) if isinstance(value,dict) else type(value).__name__}),flush=True)
  except Exception as error:out['features'][namespace+'.'+module]={'error':type(error).__name__}
except Exception as error:out['error']=type(error).__name__
p=Path('test/_analysis/oct10-pages/be72-eweb-retained.json');p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'verifiedSerial':out.get('verifiedSerial'),'error':out.get('error'),'modules':len(out['features'])}))
