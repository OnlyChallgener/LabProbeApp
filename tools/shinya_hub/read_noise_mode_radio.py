"""Read exact router identity and radio state; no firmware writes."""
import json,re,sys,time
from pathlib import Path
from remote_transport import connect_router,run

label=sys.argv[1] if len(sys.argv)>1 else 'baseline'
assert label.replace('-','').isalnum()
c=connect_router('be72')
try:
 identity=json.loads(run(c,'dev_sta get -m sysinfo'))
 assert identity.get('serial_num')=='G1TD8RX039025','Wrong router'
 radio=json.loads(run(c,'dev_config get -m radio'))
 channel=json.loads(run(c,'dev_sta get -m radio_channel'))
 game=json.loads(run(c,'dev_sta get -m rcgame'))
 details={}
 def collect(value,path=''):
  if isinstance(value,dict):
   for name,item in value.items():
    location=path+'/'+name
    if re.search(r'(bandwidth|band_width|chan.*width|htmode|vhtmode|^bw$|^width$|^channel$|^type$|^radioIndex$|^mode$)',name,re.I) and isinstance(item,(str,int,float,bool)):details[location]=item
    elif isinstance(item,(dict,list)):collect(item,location)
  elif isinstance(value,list):
   for i,item in enumerate(value):collect(item,path+'/'+str(i))
 for namespace,module in [('dev_sta','radioInfo'),('ac_config','wireless')]:
  try:collect(json.loads(run(c,namespace+' get -m '+module)),namespace+'/'+module)
  except (ValueError,RuntimeError):pass
 result={'sn':identity['serial_num'],'observedAt':int(time.time()*1000),'radio':radio,'channel':channel,
         'game':{k:game.get(k) for k in ('enabled','game','air_flowctrl')},'bandwidthDetails':details}
 out=Path('test/_analysis/oct13-official-game/rea-game/noise-mode-runtime');out.mkdir(exist_ok=True)
 (out/(label+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
 print(json.dumps({'label':label,'sn':result['sn'],'channel':channel,'bandwidthDetails':details},ensure_ascii=False))
finally:c.close()
