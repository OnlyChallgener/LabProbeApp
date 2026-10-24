"""Install audited controls and exercise readback/restoration on the two owned routers."""
import hashlib,json,shlex,sys,time
from pathlib import Path
from remote_transport import connect_router,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write

OUT=Path('test/_analysis/oct13-official-game/native-controls')
REMOTE='/usr/local/lua/dev_sta/shinya_game.lua'

def main():
 OUT.mkdir(parents=True,exist_ok=True)
 source=Path('tools/shinya_hub/router_sync/shinya_game.lua').read_bytes()
 original=source.split(b'package.path = ',1)[1].split(b'local function native(',1)[0].rstrip()
 proof=[]
 for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
  c=connect_router(family)
  try:
   assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==sn
   old=run(c,'cat '+REMOTE)
   if old!=source:
    backup=OUT/(family+'-before.lua')
    if backup.exists():
     # An earlier validated revision of this same owned overlay can be
     # replaced only when its recorded hash still matches the installed file.
     previous=OUT/(family+'-installed.sha256')
     assert previous.exists() and previous.read_text().strip()==hashlib.sha256(old).hexdigest(),'Installed helper changed; preserve it'
    else:
     assert old.split(b'package.path = ',1)[1].rstrip()==original,'Existing helper changed; preserve it'
     backup.write_bytes(old)
    temporary='/tmp/shinya-game-controls-'+str(int(time.time()))+'.lua'
    write(c,temporary,source,mode='644')
    run(c,'lua -e '+shlex.quote("assert(loadfile('"+temporary+"'))"))
    assert run(c,'cat '+REMOTE)==old
    run(c,'cp '+temporary+' '+REMOTE)
    assert run(c,'cat '+REMOTE)==source
    (OUT/(family+'-installed.sha256')).write_text(hashlib.sha256(source).hexdigest(),encoding='utf8')
   def get(ns,module):return json.loads(run(c,ns+' get -m '+module,timeout=15))
   before={m:get(ns,m) for ns,m in [('dev_sta','rcgame'),('dev_config','flowctrl_qos'),('dev_config','flowctrl'),('ac_config','lowRate')]}
   before['policy']=json.loads(run(c,'dev_sta get -m shinya_game '+shlex.quote(json.dumps({'action':'policy'}))))
   def control(data):
    value=json.loads(run(c,'dev_sta set -m shinya_game '+shlex.quote(json.dumps(data)),timeout=25))
    assert value.get('result')=='success',value
    return value
   changed={}
   enabled=next(r['enable']=='on' for r in before['flowctrl_qos']['list'] if r['comment']=='games')
   band=int(next(r['downloadBand'] for r in before['flowctrl']['list'] if r['ifname']=='br-wan'))
   start=before['lowRate']['Start'];profile={'McsPriority':'1','BwPriority':'1','NssPriority':'1','ModePriority':'1'}
   from importlib.util import spec_from_file_location,module_from_spec
   sys.path.insert(0,'D:/Github/ShinyaHub')
   from shinya_hub.game_acceleration import flow_mode
   mode=flow_mode(before['policy']);assert mode in (0,1),'Unknown initial flow profile; preserve it'
   try:
    assert before['rcgame']['air_flowctrl']==('1' if enabled else '0'),'Preexisting mixed priority requires separate restoration'
    changed['priority']=control({'action':'gamePriority','enable':not enabled})
    control({'action':'gamePriority','enable':enabled})
    changed['flowLevel']=control({'action':'flowMode','mode':1-mode})
    control({'action':'flowMode','mode':mode})
    changed['band']=control({'action':'flowBand','band':band+1 if band<3000 else band-1})
    control({'action':'flowBand','band':band})
    control({'action':'flowMode','mode':mode})
    if all(str(start[k])==v for k,v in profile.items()):profile={'McsPriority':'1','BwPriority':'2','NssPriority':'3','ModePriority':'4'}
    run(c,'ac_config update -m lowRate '+shlex.quote(json.dumps({'Start':profile})),timeout=20)
    low=get('ac_config','lowRate');assert all(str(low['Start'][k])==v for k,v in profile.items());changed['mode']=profile
    wanted='0' if str(start['Enable'])=='1' else '1'
    run(c,'ac_config update -m lowRate '+shlex.quote(json.dumps({'Enable':wanted})),timeout=20)
    low=get('ac_config','lowRate');assert str(low['Start']['Enable'])==wanted;changed['weakSignalGuard']=int(wanted)
   finally:
    control({'action':'gamePriority','enable':enabled})
    control({'action':'flowBand','band':band})
    run(c,'ac_config update -m lowRate '+shlex.quote(json.dumps({'Start':{k:start[k] for k in profile}})),timeout=20)
    run(c,'ac_config update -m lowRate '+shlex.quote(json.dumps({'Enable':str(start['Enable'])})),timeout=20)
    after={m:get(ns,m) for ns,m in [('dev_sta','rcgame'),('dev_config','flowctrl_qos'),('dev_config','flowctrl'),('ac_config','lowRate')]}
    assert after['rcgame']['air_flowctrl']==before['rcgame']['air_flowctrl']
    assert after['rcgame']['game']==before['rcgame']['game']
    assert after['flowctrl_qos']['list']==before['flowctrl_qos']['list']
    assert after['flowctrl']['list']==before['flowctrl']['list']
    assert after['lowRate']['Start']==before['lowRate']['Start']
    after_policy=json.loads(run(c,'dev_sta get -m shinya_game '+shlex.quote(json.dumps({'action':'policy'}))))
    assert after_policy==before['policy']
   row={'family':family,'serial':sn,'tested':changed,'restored':True,'helperSha256':hashlib.sha256(source).hexdigest()}
   proof.append(row);print(json.dumps(row),flush=True)
  finally:c.close()
 (OUT/'proof.json').write_text(json.dumps(proof,indent=2),encoding='utf8')

if __name__=='__main__':main()
