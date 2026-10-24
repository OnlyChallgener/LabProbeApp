"""Exercise the actual bundle's assistant socket without network or a device."""
import argparse
import json
from pathlib import Path
from patch_bundle import load
from verify_terminal_ipv6_patch import VM, Env, Fn, check_read_caches
from verify_station_lookup import check_frame


class BridgeVM(VM):
    def __init__(self, reader):
        super().__init__(reader)
        self.events = []
        self.pending = []
        self.opened = []
        self.globals['console'] = {'log': Fn(lambda _, *args: None)}
        self.globals['JSON']['stringify']=Fn(lambda _,value:json.dumps(value))
        self.globals['setInterval']=Fn(lambda _,callback,delay:self.timer(callback,delay))
        self.globals['clearInterval']=Fn(lambda _,timer:self.timers.pop(timer,None))

    def prop(self, obj, name):
        if isinstance(obj, list) and name == 'forEach':
            return Fn(lambda _, callback: [callback.call(None,[item]) for item in obj])
        return super().prop(obj, name)

    def closure(self, fid, env):
        # Isolate only the original complex message renderer. The transport,
        # onopen, send, result parser and close execute actual patched bytecode.
        if fid == 35712:
            return Fn(lambda _, event: self.events.append((env,event)))
        return super().closure(fid, env)


def main():
    p=argparse.ArgumentParser();p.add_argument('--bundle',type=Path,required=True);a=p.parse_args()
    r=load(a.bundle.read_bytes())
    for fid in (35710,35717,23461,23465,23460,23459):
        check_frame(r,fid);check_read_caches(r,fid)
    assert r.function_headers[35710].environmentSize >= 5
    vm=BridgeVM(r)
    module=Env();helper=Env(module);hook=Env(helper)
    module.slots[6]={'NativeModules':{'sta':{'assistantSend':Fn(
        lambda _,payload,cb:vm.pending.append((payload,cb)))}}}
    helper.slots[12]=Fn(lambda _, socket:vm.opened.append(socket))
    socket=vm.run(35710,hook)
    assert set(socket)=={'onopen','onmessage','onerror','send','close'}
    timer,delay=next(iter(vm.timers.values()))
    assert delay==20
    timer.call(None,[])
    assert vm.opened==[socket]
    interval_id,(poll,poll_delay)=next((k,v) for k,v in vm.timers.items() if v[1]==1000)
    assert poll_delay==1000
    poll.call(None,[])
    assert not vm.pending, 'poll before a scoped user payload must be idle'
    socket['send'].call(socket,['{"requestMessageType":"init"}'])
    assert len(vm.pending)==1 and vm.pending[0][0]=='{"requestMessageType":"init"}'
    callback=vm.pending[0][1]
    callback.call(None,[''])
    assert not vm.events
    events=[{'data':'{"messageDetail":"one"}'},{'data':'{"messageDetail":"two"}'}]
    callback.call(None,[json.dumps(events)])
    assert [event for _,event in vm.events]==events
    poll.call(None,[])
    assert len(vm.pending)==2
    assert json.loads(vm.pending[-1][0])['requestMessageType']=='poll'
    socket['close'].call(socket,[])
    assert interval_id not in vm.timers
    callback.call(None,[json.dumps(events)])
    socket['send'].call(socket,['ignored after close'])
    assert len(vm.pending)==2 and len(vm.events)==2
    second=vm.run(35710,hook)
    second['send'].call(second,['new session'])
    assert len(vm.pending)==3
    vm.pending[-1][1].call(None,[json.dumps(events[:1])])
    assert len(vm.events)==3 and vm.events[-1][0] is not vm.events[0][0]
    source=load((Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
    for fid in (35712,35719):
        before,after=source.function_headers[fid],r.function_headers[fid]
        source.file_buffer.seek(before.offset);r.file_buffer.seek(after.offset)
        assert source.file_buffer.read(before.bytecodeSizeInBytes)==r.file_buffer.read(after.bytecodeSizeInBytes)
    assert r.strings[473]=='Helper'
    banner_env=Env();navigations=[]
    banner_env.slots[5]={'navigate':Fn(lambda _,route:navigations.append(route))}
    rows=vm.run(23460,banner_env)
    assert len(rows)==1 and rows[0]['content']=='小星助手 V2.0'
    rows[0]['onClick'].call(None,[])
    assert navigations==['Helper']
    print('PASS: open, native send, two events, empty result, close, late callback, reopen; original renderer/reconnect unchanged')


if __name__=='__main__': main()
