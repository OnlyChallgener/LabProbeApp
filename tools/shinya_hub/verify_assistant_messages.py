"""Exercise actual message reducers with interleaved chat and task updates."""
import argparse
from pathlib import Path
from patch_bundle import load
from verify_assistant_notification_navigation import VM as BaseVM
from verify_terminal_ipv6_patch import Env,Fn,check_read_caches
from verify_station_lookup import check_frame

class VM(BaseVM):
    def prop(self,obj,key):
        if isinstance(obj,list) and key=='slice':return Fn(lambda _,*args:list(obj))
        if isinstance(obj,list) and key=='push':return Fn(lambda _,value:obj.append(value) or len(obj))
        return super().prop(obj,key)

def main():
    p=argparse.ArgumentParser();p.add_argument('bundle',type=Path);a=p.parse_args();r=load(a.bundle.read_bytes())
    vm=VM(r);env=Env();rows=[]
    for fid in (35733,35734,8557,8558):check_frame(r,fid);check_read_caches(r,fid)
    def receive(message,fid=35734):
        nonlocal rows
        previous=list(rows);env.slots[1]=message;rows=vm.run(fid,env,(rows,))
        assert previous is not rows
    receive({'uuid':'task1','key':'stable','sender':'other','type':'hubCard','value':0})
    receive({'uuid':'','key':'user','sender':'user','type':'chat','value':'hello'})
    for i in range(100):receive({'uuid':'task1','key':str(i),'sender':'other','type':'hubCard','value':i})
    assert len(rows)==2 and rows[0]['key']=='stable' and rows[0]['value']==99 and rows[1]['key']=='user'
    receive({'uuid':'task2','key':'next','sender':'other','type':'hubCard'})
    receive({'uuid':'','key':'user2','sender':'user','type':'chat'})
    assert len(rows)==4
    # The original diagnosis reducer owns its separate progress lifecycle.
    # Verify restoration directly, rather than requiring V2 key semantics.
    from patch_assistant_history import BASELINE
    original=load(BASELINE.read_bytes())
    actual_header=r.function_headers[35733];original_header=original.function_headers[35733]
    actual_bytes=a.bundle.read_bytes()[actual_header.offset:actual_header.offset+actual_header.bytecodeSizeInBytes]
    original_bytes=BASELINE.read_bytes()[original_header.offset:original_header.offset+original_header.bytecodeSizeInBytes]
    assert actual_bytes==original_bytes, 'Diagnosis reducer must retain its original lifecycle'
    saved={};native={'hGet':Fn(lambda _,scope:saved.get(scope)),
                    'hSet':Fn(lambda _,scope,value:saved.__setitem__(scope,value) or True)}
    vm.globals['__r']=Fn(lambda _,mid:{'NativeModules':{'sta':native}} if mid==18 else None)
    assert vm.run(8558,Env(),('scope-A',)) is None
    assert vm.run(8557,Env(),('scope-A','[{"key":"retained"}]')) is True
    assert vm.run(8558,Env(),('scope-A',))=='[{"key":"retained"}]'
    assert vm.run(8558,Env(),('scope-B',)) is None
    print('PASS 100 V2 updates, stable key/position, interleaved chat, original diagnosis reducer and durable storage')

if __name__=='__main__':main()
