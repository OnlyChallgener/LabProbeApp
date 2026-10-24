from pathlib import Path
import argparse
from patch_bundle import load
from verify_terminal_ipv6_patch import VM,Env,Fn,check_read_caches
from verify_station_lookup import check_frame
p=argparse.ArgumentParser();p.add_argument('--bundle',required=True,type=Path);a=p.parse_args()
r=load(a.bundle.read_bytes());v=VM(r);calls=[]
native={'openProjectPages':Fn(lambda _,project,mode:calls.append((project,mode))), 'assistantSpeedtest':Fn(lambda _,project:calls.append(('speed',project)))}
v.globals['__r']=Fn(lambda _,module:{'NativeModules':{'sta':native}})
for fid in (21901,22907):
    v.run(fid,Env());assert calls.pop()==('', 'add');check_frame(r,fid);check_read_caches(r,fid)
module=Env();module.slots[6]={'NativeModules':{'sta':native}}
helper=Env(module);helper.slots[23]='test-project'
v.run(35703,helper);assert calls.pop()==('speed','test-project');check_frame(r,35703)
print('PASS: both add entries and assistant speed confirmation use native methods and captured project')
