"""Execute restored native speed entries and original header renderer."""
import argparse,hashlib,json
from pathlib import Path
from patch_bundle import load
from patch_speed_entry import *
from verify_assistant_notification_navigation import VM
from verify_terminal_ipv6_patch import Env,Fn,check_read_caches
from verify_station_lookup import check_frame
from hbc_grow import assert_bodies_mapped

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--bundle',type=Path);args=ap.parse_args()
    b=(Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
    if args.bundle:d=args.bundle.read_bytes();r=load(d)
    else:
        d=additions(load(b),bytearray(b),set(),[]);d[-20:]=hashlib.sha1(d[:-20]).digest();r=load(bytes(d))
    assert_bodies_mapped(r)
    for f in (OPEN,23925,33130,33131):check_frame(r,f);check_read_caches(r,f)
    original=load(b)
    assert r.function_headers[33130].frameSize==original.function_headers[33130].frameSize==69
    assert r.function_headers[33130].environmentSize==original.function_headers[33130].environmentSize==22
    vm=VM(r);module=Env();page=Env(module);calls=[]
    native={'speedTest':Fn(lambda _,p,s:calls.append((p,s)))}
    rn={'NativeModules':{'sta':native},'View':'View','Text':'Text','TouchableNativeFeedback':'Touch'}
    state={'network':{'curProject':{'projectId':'A'}}}
    store={'store':{'getState':Fn(lambda _:state)}}
    module.slots.update({0:Fn(lambda _,dep:store if dep==1934 else None),5:rn,12:rn});page.slots[19]={'projectId':'STALE'};page.slots[3]={'projectId':'STALE'}
    vm.run(23925,page);assert calls==[('A','')]
    state['network']['curProject']=None;vm.run(23925,page);assert len(calls)==1
    state['network']['curProject']={'projectId':'B'};vm.run(OPEN,page);assert calls[-1]==('B','')
    state['network']['curProject']=None;vm.run(OPEN,page);assert len(calls)==2
    element=Fn(lambda _,type,props:{'type':type,'props':props})
    jsx={'jsx':element,'jsxs':element};widgets={'Flex':'Flex','Icon':'Icon'}
    module.slots.update({0:Fn(lambda _,dep:store if dep==1934 else jsx if dep==22 else widgets),1:list(range(24)),2:{'default':Fn(lambda _,cb:Fn(lambda _:None))}})
    state['network']['curProject']={'projectId':'P'};page.slots.update({7:'12:30'})
    tree=vm.run(33131,page);texts=[];handlers=[]
    def visit(value):
        if isinstance(value,dict):
            if value.get('type')=='Text':texts.append(value['props'].get('children'))
            if value.get('type')=='Touch':handlers.append(value['props']['onPress'])
            for v in value.values():visit(v)
        elif isinstance(value,list):
            for v in value:visit(v)
    visit(tree);assert '测网速' in texts and len(handlers)==2,(texts,handlers)
    handlers[0].call(None,[]);assert calls[-1]==('P','')
    state['network']['curProject']={'projectId':'NEXT'};handlers[0].call(None,[]);assert calls[-1]==('NEXT','')
    print('PASS Tools/header clicks read current store including a project switch; missing-project refusal; original Speed render frame/environment')

if __name__=='__main__':main()
