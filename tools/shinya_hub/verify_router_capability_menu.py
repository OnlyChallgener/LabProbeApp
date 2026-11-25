"""Execute the actual router-menu capability gate; no model-based inference."""
import argparse
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from verify_station_lookup import check_frame
from verify_terminal_ipv6_patch import check_read_caches

def check(reader):
 fid=24024;check_frame(reader,fid);check_read_caches(reader,fid)
 instructions=list(parse_hbc_bytecode(reader.function_headers[fid],reader))
 start=max(i.original_pos for i in instructions if i.inst.name=='GetEnvironment' and i.arg1==2 and i.arg2==0)
 code={i.original_pos:i for i in instructions}
 def run(device):
  rows=list(range(8));r={0:rows};pc=start
  for _ in range(100):
   i=code[pc];name=i.inst.name;a=[getattr(i,'arg'+str(k+1)) for k in range(len(i.inst.operands))];nxt=pc+1+len(bytes(i.inst.structure()))
   if name=='GetEnvironment':r[a[0]]={3:device}
   elif name=='LoadFromEnvironment':r[a[0]]=r[a[1]].get(a[2])
   elif name=='LoadConstString':r[a[0]]=reader.strings[a[1]]
   elif name=='GetByVal':
    value,key=r[a[1]],r[a[2]]
    r[a[0]]=value[key] if isinstance(value,list) else value.get(key) if isinstance(value,dict) else None
   elif name=='LoadConstFalse':r[a[0]]=False
   elif name=='LoadConstUInt8':r[a[0]]=a[1]
   elif name=='JmpFalseLong':
    if not r[a[1]]:nxt=pc+a[0]
   elif name=='JStrictNotEqualLong':
    if type(r[a[1]])!=type(r[a[2]]) or r[a[1]]!=r[a[2]]:nxt=pc+a[0]
   elif name=='PutOwnByIndex':
    assert a[0]==0
    rows[a[2]]=r[a[1]]
   elif name=='PutByVal':
    assert r[a[0]] is rows and r[a[1]]=='length'
    del rows[r[a[2]]:]
   elif name=='Ret':return r[a[0]]
   else:raise AssertionError(name)
   pc=nxt
  raise AssertionError('gate did not settle')
 cases=0
 for value,visible in [(False,False),(True,True),(None,True),(0,True),('false',True),({},True)]:
  rows=run({'capabilities':{'routerTools':{'firewall':value}},'isMain':True})
  assert rows==(list(range(8)) if visible else [*range(6),7]);cases+=1
 for device in [None,{}, {'isMain':False},{'capabilities':{}},{'capabilities':{'routerTools':{}}}]:
  assert run(device)==list(range(8));cases+=1
 unsupported={'capabilities':{'routerTools':{'firewall':False}}};supported={'capabilities':{'routerTools':{'firewall':True}}}
 assert [len(run(x)) for x in (unsupported,supported,unsupported)]==[7,8,7];cases+=1
 print('PASS',cases,'router capability menu cases: firewall gated independently of port mapping')

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('bundle',type=Path);a=p.parse_args();check(load(a.bundle.read_bytes()))
