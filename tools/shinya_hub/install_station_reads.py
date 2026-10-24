"""Install a new read-only UF module on the two explicitly authorized routers."""
import sys
from pathlib import Path
from remote_transport import connect_router,run
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import write
def main():
 data=Path('tools/shinya_hub/router_sync/shinya_station.lua').read_bytes()
 for family in ('be72','be50'):
  c=connect_router(family)
  try:
   write(c,'/tmp/shinya_station-reviewed.lua',data)
   run(c,"lua -e 'assert(loadfile(\"/tmp/shinya_station-reviewed.lua\"))'")
   run(c,'cp /tmp/shinya_station-reviewed.lua /usr/local/lua/dev_sta/shinya_station.lua')
   print(family,run(c,"dev_sta get -m shinya_station '{\"action\":\"rdp\",\"ip\":\"192.168.5.201\"}'",timeout=12).decode() if family=='be72' else 'read-only module installed',flush=True)
   value=run(c,"dev_sta get -m shinya_station '{\"action\":\"history\"}'",timeout=15)
   Path('test/_analysis/oct11-device-controls/'+family+'-native-events.json').write_bytes(value)
   print(family,'native history bytes',len(value),flush=True)
  finally:c.close()
if __name__=='__main__':main()
