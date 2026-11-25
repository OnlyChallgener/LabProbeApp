"""Execute the real post-await history path with old check/optimize states."""
import sys
from pathlib import Path
from patch_bundle import load
from verify_assistant_diagnosis_detail import VM,U
from verify_terminal_ipv6_patch import Env,Fn,check_read_caches
from verify_station_lookup import check_frame

def main():
    root=Path(__file__).resolve().parents[2]
    r=load(Path(sys.argv[1]).read_bytes())
    before=load((root/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
    for fid in (35513,35523):check_frame(r,fid);check_read_caches(r,fid)
    old=before.function_headers[35523];new=r.function_headers[35523]
    before.file_buffer.seek(old.offset);r.file_buffer.seek(new.offset)
    assert before.file_buffer.read(0xbf)==r.file_buffer.read(0xbf), 'request/await/empty-history behavior changed'
    assert [(x.start,x.end,x.target) for x in before.function_id_to_exc_handlers[35523]]==[(x.start,x.end,x.target) for x in r.function_id_to_exc_handlers[35523]]
    for optimized in (None,False,'success','fail'):
        calls=[];module=Env();component=Env(module)
        module.slots.update({4:{'default':{'remove':Fn(lambda _,toast:calls.append(('remove',toast)))}},8:{'showResult':'showResult'},10:{'check':'check','optimize':'optimize'}})
        for slot,name in ((9,'result'),(7,'id'),(10,'tag'),(3,'phase')):
            component.slots[slot]=Fn(lambda _,value,name=name:calls.append((name,value)))
        component.slots[8]={'sn':'router-A'}
        snapshot={'id':'saved','allCheckStatus':'success','allOptimizeStatus':optimized,'endTime':'recent','detailMap':{}}
        vm=VM(r)
        vm.run(35523,component,initial={2:module,5:component,6:snapshot,11:'router-A',1:U,3:'old-toast'},start=0xbf)
        assert calls==[('result',snapshot),('id','saved'),('tag','optimize' if optimized else 'check'),('phase','showResult'),('remove','old-toast')],calls
        calls.clear();component.slots[8]['sn']='router-B'
        vm.run(35523,component,initial={2:module,5:component,6:snapshot,11:'router-A',1:U,3:'old-toast'},start=0xbf)
        assert calls==[('remove','old-toast')], 'late other-router response updated current page'
    print('PASS history-only rendering for 4 check/optimize states and 4 stale-router replies; retained request/empty/catch paths; no active check/optimization phase')

if __name__=='__main__':main()
