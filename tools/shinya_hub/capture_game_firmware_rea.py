"""Read exact BE72 game artifacts for reference comparison; no router writes."""
import hashlib,json
from pathlib import Path
from remote_transport import connect_router,run
out=Path('test/_analysis/oct13-official-game/rea-game/firmware');out.mkdir(parents=True,exist_ok=True)
c=connect_router('be72')
try:
 assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']=='G1TD8RX039025'
 records=[]
 for path in ('/usr/sbin/rcgamed','/usr/sbin/low_rate.elf','/usr/local/lua/ac_config/lowRate.lua','/usr/local/lua/dev_sta/rcgame.lua'):
  raw=run(c,'cat '+path,timeout=20);name=Path(path).name;(out/name).write_bytes(raw)
  records.append({'path':path,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
 (out/'identity.json').write_text(json.dumps({'sn':'G1TD8RX039025','files':records},indent=2),encoding='utf-8');print(json.dumps(records))
finally:c.close()
