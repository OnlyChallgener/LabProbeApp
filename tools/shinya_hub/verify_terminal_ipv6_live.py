"""Read-only deployed collector and private route smoke; no credentials in output."""
import json, os, sys
from pathlib import Path
import read_router_ipv6_sources
from remote_transport import connect_nas, run

CODE = '''
import json,time
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceKey,DeviceDriver,DeviceRegistry
from shinya_hub.api import create_app,Principal,token_digest
from shinya_hub.station_ipv6 import STATION_IPV6,observed_candidates
settings=ConfigVault(Path('/config')).load()
s=ProjectStore(Path('/data/shinya.sqlite')); r=DeviceRegistry(s)
try:
 for d in settings['devices']:
  k=DeviceKey(d['tenant'],d['project'],d['sn'])
  r.register(k,DeviceDriver(d['address'],d['password'],d['family'],expected_sn=k.sn))
  value=r.refresh(k,STATION_IPV6).result(timeout=45)
  snap=s.cached(k.tenant,k.project,k.sn,r.resource(STATION_IPV6))
  assert isinstance(value,dict) and 'neighbors' in value, list(value) if isinstance(value,dict) else type(value).__name__
  candidates=observed_candidates(snap,time.time())
  app=create_app(s,r,{token_digest('ipv6-live-smoke'):Principal(k.tenant,'IPv6 smoke',False)})
  res=app.test_client().post('/api/v1/hub/station/ipv6',headers={'Authorization':'Bearer ipv6-live-smoke'},json={'projectId':k.project,'sn':k.sn,'mac':'da:1f:85:0c:19:fc'}).get_json()
  assert res['code']==0,res.get('msg')
  assert 'ipv6Public' in res['data'] and 'ipv6DisplayUntil' in res['data']
  print(json.dumps({'sn':k.sn,'collector':True,'qualifiedCandidates':len(candidates),'route':res['code'],'targetHasPublic':bool(res['data']['ipv6Public'])}),flush=True)
finally:r.close()
'''

def main():
    c=connect_nas()
    try:
        result=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=110)
        Path('test/_analysis/oct16-terminal-ipv6/live-route-check.txt').write_bytes(result)
        print(result.decode().strip())
    finally:c.close()

if __name__=='__main__':main()
