"""Execute the version-history getter from the actual patched Hermes bundle."""
import argparse
from pathlib import Path
from patch_bundle import load
from verify_terminal_ipv6_patch import VM,Env,Fn
from verify_station_lookup import check_frame
p=argparse.ArgumentParser();p.add_argument('--bundle',type=Path,required=True);a=p.parse_args()
r=load(a.bundle.read_bytes());vm=VM(r);module=Env();query=Env(module)
rows=[{'version':'1.2.4-hub.'+str(n),'releaseId':str(183249840+n),'msg':'entry-'+str(n)} for n in range(79,73,-1)]
calls=[]
module.slots[0]=Fn(lambda _,dependency:{'getAppVersionList':Fn(lambda __:calls.append('getAppVersionList') or rows)})
module.slots[1]=[None]*7+['version-client']
result=vm.run(23578,query)
assert calls==['getAppVersionList'] and result==rows, 'Version history getter was replaced or no longer returns HUB results'
check_frame(r,23578)
print('PASS: actual version-history getter returns all six HUB entries through its API client')
