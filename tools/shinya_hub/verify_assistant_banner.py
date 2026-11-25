"""Execute scoped carousel helpers from their actual patched Hermes bodies."""
import hashlib,json
from pathlib import Path
from patch_bundle import load
from patch_assistant_banner import *
from verify_assistant_notification_navigation import VM as BaseVM
from verify_terminal_ipv6_patch import Env,Fn,check_read_caches
from verify_station_lookup import check_frame
from hbc_grow import assert_bodies_mapped

class VM(BaseVM):
    def prop(self,obj,key):
        if isinstance(obj,list) and key=='map':return Fn(lambda _,f:[f.call(None,[v]) for v in obj])
        return super().prop(obj,key)

def main():
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--bundle',type=Path);args=parser.parse_args()
    b=(Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
    assert hashlib.sha256(b).hexdigest()=='673845c79b7a4c4e18aa80d7ead95fd7e06bc3523e29decb2b2ecca064f763a5'
    if args.bundle:r=load(args.bundle.read_bytes())
    else:
        d=additions(load(b),bytearray(b),set(),[]);d[-20:]=hashlib.sha1(d[:-20]).digest();r=load(bytes(d))
        for s,v in STRINGS.items():r.strings[s]=v
        r.strings[30092]='assistantHistoryScope';r.strings[344]='小星助手 V2.0'
    assert_bodies_mapped(r)
    assert r.function_headers[24141].frameSize==load(b).function_headers[24141].frameSize, 'preserve original Construct outgoing slots'
    for f in (23460,24141,24142,24144):check_frame(r,f);check_read_caches(r,f)
    vm=VM(r);module=Env();first=Env(module);second=Env(module)
    a=vm.run(23460,first);b=vm.run(23460,second)
    assert len(a)==len(b)==1 and a is not b
    assert a[0]['onClick'] is not b[0]['onClick'], 'navigation closure must remain component-owned'
    for fid in (24142,24144):
        assert vm.run(fid,first) is None, 'fixed entry must not fetch messages or start a carousel timer'
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    for fid in (24142,24144):
        names=[i.inst.name for i in parse_hbc_bytecode(r.function_headers[fid],r)]
        assert names==['LoadConstUndefined','Ret'], (fid,names)
    assert a[0]['content']==r.strings[344]
    print('PASS fixed original entry, component-owned click closures, no feed or carousel effect; frame/cache/mapping')

if __name__=='__main__':main()
