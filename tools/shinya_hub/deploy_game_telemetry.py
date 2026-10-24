"""Update only the owned telemetry reader, without exercising controls."""
import hashlib,json,shlex,sys,time
from pathlib import Path
from remote_transport import connect_router,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write
OUT=Path('test/_analysis/oct13-official-game/game-log-history');OUT.mkdir(exist_ok=True)
NATIVE=Path('test/_analysis/oct13-official-game/native-controls')
REMOTE='/usr/local/lua/dev_sta/shinya_game.lua'
def main():
 data=Path('tools/shinya_hub/router_sync/shinya_game.lua').read_bytes();proof=[]
 for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
  c=connect_router(family)
  try:
   assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==sn
   old=run(c,'cat '+REMOTE);record=NATIVE/(family+'-installed.sha256')
   assert record.read_text().strip()==hashlib.sha256(old).hexdigest(),'Native helper changed'
   if old!=data:
    (OUT/(family+'-before.lua')).write_bytes(old)
    temp='/tmp/shinya-game-telemetry-'+str(int(time.time()))+'.lua'
    write(c,temp,data,mode='644');run(c,'lua -e '+shlex.quote("assert(loadfile('"+temp+"'))"))
    assert old==run(c,'cat '+REMOTE);run(c,'cp '+temp+' '+REMOTE)
    assert data==run(c,'cat '+REMOTE);record.write_text(hashlib.sha256(data).hexdigest())
   value=json.loads(run(c,'dev_sta get -m shinya_game '+shlex.quote('{"action":"telemetry"}')))
   assert value['source']=='firmware.rcgame'
   (OUT/(family+'-telemetry.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
   evidence=run(c,r"find /tmp/log /tmp/game_wifi /tmp/mvp_edge/bus_upload -maxdepth 1 -type f 2>/dev/null | sed -n '/channel\|optimi\|rcgame_status\|low_rate/p'; true")
   (OUT/(family+'-event-files.txt')).write_bytes(evidence)
   proof.append({'family':family,'sn':sn,'eventBytes':len(value.get('events','')),'configWrites':False});print(json.dumps(proof[-1]),flush=True)
  finally:c.close()
 (OUT/'deployment.json').write_text(json.dumps(proof,indent=2))
if __name__=='__main__':main()
