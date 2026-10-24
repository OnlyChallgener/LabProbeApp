"""Update only the independent rate collector, then the independent HUB image."""
import importlib.util,json,os,time
from pathlib import Path
from remote_transport import connect_router,run

def text(value):return value.decode('utf8','replace') if isinstance(value,bytes) else value
stamp=str(int(time.time()))
for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
 c=connect_router(family)
 try:
  assert json.loads(text(run(c,'dev_sta get -m sysinfo')))['serial_num']==sn
  before=text(run(c,"ps w | sed -n '/[s]hinya_mqlink.elf/p'")).strip()
  backup='/etc/shinya-agent/collector.pre-rate-'+stamp+'.lua'
  run(c,'cp /etc/shinya-agent/collector.lua '+backup)
  for name in ('wifi_rates.lua','collector.lua'):
   target='/etc/shinya-agent/'+name
   data=(Path('tools/shinya_hub/router_sync')/name).read_bytes()
   run(c,'umask 077; cat > '+target+'.new',stdin=data)
   run(c,"lua -e "+__import__('shlex').quote("assert(loadfile('"+target+".new'))"))
   run(c,'mv '+target+'.new '+target)
  run(c,'/etc/init.d/shinya-agent restart',timeout=20)
  after=text(run(c,"ps w | sed -n '/[s]hinya_mqlink.elf/p'")).strip()
  assert before==after,'Official MQTT process changed unexpectedly'
  print(json.dumps({'family':family,'backup':backup,'officialProcessPreserved':True}),flush=True)
 finally:c.close()

path=Path('D:/Github/ShinyaHub/scripts/update_nas.py')
spec=importlib.util.spec_from_file_location('rate_deploy',path)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.IMAGE='shinya-hub:oct10-pages-rates-v28'
module.main()
