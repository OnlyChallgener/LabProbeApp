"""Restore only the measured baseline 5 GHz channel and verify readback."""
import json,shlex,time
from pathlib import Path
from remote_transport import connect_router,run

baseline=json.loads(Path('test/_analysis/oct13-official-game/rea-game/noise-mode-runtime/baseline.json').read_text(encoding='utf8'))
before=next(r for r in baseline['radio']['radioList'] if r['type']=='5G')
assert baseline['sn']=='G1TD8RX039025' and str(before['channel']).isdigit()
c=connect_router('be72')
try:
 assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==baseline['sn']
 current=json.loads(run(c,'dev_config get -m radio'))
 radios=[r for r in current['radioList'] if r.get('type')=='5G'];assert len(radios)==1
 if radios[0]['channel']!=before['channel']:
  radios[0]['channel']=before['channel']
  data={k:v for k,v in current.items() if k not in {'configId','configTime','currentTime','subConfigId','version'}}
  run(c,'dev_config set -m radio '+shlex.quote(json.dumps(data,separators=(',',':'))))
 for attempt in range(10):
  current=json.loads(run(c,'dev_sta get -m radio_channel'));bands=current['band'].split(',');channels=current['cur_channel'].split(',')
  if dict(zip(bands,channels)).get('5G')==before['channel']:break
  time.sleep(1)
 else:raise RuntimeError('Baseline channel readback did not match')
 print(json.dumps({'sn':baseline['sn'],'restored5GChannel':before['channel'],'verified':True}))
finally:c.close()
