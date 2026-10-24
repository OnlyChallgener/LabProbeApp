"""Reference-only official operations, with per-operation restoration and native snapshots.

This investigative script is never part of the deployed HUB. Credentials remain
inside the existing NAS vault. It does not reboot the router or optimize Wi-Fi.
"""
import os
from pathlib import Path
from remote_transport import connect_nas, run

PROBE = r'''
import json,time,requests
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.devices import DeviceDriver
from shinya_hub.commands import UFCommand
settings=ConfigVault(Path('/config')).load()
saved=ConfigVault(Path('/data/official-account')).load()
binding=next(r for r in saved['devices'] if r['sn']=='G1TD8RX039025')
credential=next(r for r in saved['credentials'] if r['tenant']==binding['tenant'])
device=next(r for r in settings['devices'] if r['sn']==binding['sn'])
driver=DeviceDriver(device['address'],device['password'],device['family'],expected_sn=binding['sn'])
s=requests.Session();s.trust_env=False
login=s.post('https://shinyaapp.ruijie.com.cn/api/v1/base/homeUser/accountPasswordLogin',
 json={'account':credential['account'],'password':credential['password']},timeout=15).json()
if login.get('code')!=0 or not login.get('data',{}).get('token'):raise RuntimeError('Reference login failed')
s.headers.update({'Authorization':'Bearer '+login['data']['token'],'type':'shinyaApp','projectId':binding['officialProjectId']})
def call(leaf,extra=None):
 r=s.post('https://shinyaapp.ruijie.com.cn/mvp/homewlan/eSportAccelerate/'+leaf,
  json={'projectId':binding['officialProjectId'],**(extra or {})},timeout=18)
 v=r.json()
 if v.get('code')!=0:raise RuntimeError('Reference operation rejected: '+leaf)
 return v.get('data')
flags={k:True for k in ['isGetRcgameStatus','isGetNoiseReductionStatus','isGetLowRateStatus',
 'isGetFlowCtrlQosStatus','isGetFlowCtrlStatus','isGetPowerEconomize','isGetLowRateMode','isGetCtsEnable']}
def clean(v):
 if isinstance(v,dict):return {k:clean(x) for k,x in v.items() if not any(t in k.lower() for t in ('password','passwd','psk','secret','token','credential','key'))}
 if isinstance(v,list):return [clean(x) for x in v]
 return v
def snapshot():
 out={}
 for ns,module in [('dev_sta','rcgame'),('dev_config','flowctrl'),('dev_config','flowctrl_qos'),('ac_config','lowRate'),('ac_config','wireless')]:
  try:out[module]=clean(driver.execute(UFCommand(ns,'get',module,None)))
  except Exception as e:out[module]={'errorType':type(e).__name__}
 return out
before=call('getEsportAccelerateStatus',flags)
out={'initialStatus':before,'initialFirmware':snapshot(),'cases':[]}
def measure(leaf,field,wanted,restore_leaf=None):
 original=before.get(field)
 if original is None:raise RuntimeError('Initial field unavailable: '+field)
 row={'method':leaf,'field':field,'original':original,'wanted':wanted}
 applied=False
 try:
  body={field:wanted} if leaf.startswith('set') else {}
  applied=True;call(leaf,body);time.sleep(2)
  row['status']=call('getEsportAccelerateStatus',flags);row['firmware']=snapshot()
 finally:
  if applied:
   undo=restore_leaf or leaf
   call(undo,{field:original} if undo.startswith('set') else {});time.sleep(2)
   row['restoredStatus']=call('getEsportAccelerateStatus',flags);row['restoredFirmware']=snapshot()
   if row['restoredStatus'].get(field)!=original:raise RuntimeError('Reference state restoration mismatch: '+field)
  out['cases'].append(row)
  print(json.dumps({'case':leaf,'field':field,'wanted':wanted,'confirmed':row.get('status',{}).get(field),'restored':row.get('restoredStatus',{}).get(field)},ensure_ascii=False),flush=True)
measure('setFlowCtrlMode','flowCtrlMode',1-int(before['flowCtrlMode']))
measure('setFlowCtrlBand','flowCtrlBand',int(before['flowCtrlBand']))
measure('setLowRateMode','lowRateMode',1-int(before['lowRateMode']))
for name,field in [('GamePriority','gamePriority'),('PowerEconomize','powerEconomize'),('StaEconomize','ctsEnable')]:
 original=bool(before[field]);measure(('close' if original else 'open')+name,field,not original,('open' if original else 'close')+name)
print('RESULT_JSON '+json.dumps(out,ensure_ascii=False),flush=True)
'''
if __name__=='__main__':
    client=connect_nas()
    try:
        wrapped='try:\n'+''.join(' '+line+'\n' for line in PROBE.splitlines())+'except Exception as e:\n print("REFERENCE_ERROR",type(e).__name__,str(e),flush=True)\n if "out" in globals():print("RESULT_JSON "+json.dumps(out,ensure_ascii=False),flush=True)\n'
        value=run(client,'docker exec -i shinya-hub python -u -',stdin=wrapped.encode(),
                  sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=240).decode()
        out=Path('test/_analysis/oct13-official-game/native-operation-reference.txt')
        out.write_text(value,encoding='utf8')
        print('\n'.join(line for line in value.splitlines() if not line.startswith('RESULT_JSON ')))
        print('Reference snapshots saved; no game cloud dependency deployed')
    finally:client.close()
