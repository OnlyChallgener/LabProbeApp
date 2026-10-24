"""Execute the exact shipped stats bytecode and check removed component roots."""
import sys
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

r=load(Path(sys.argv[1]).read_bytes())
assert r.strings[r.function_headers[23571].functionName]=='Setting'
assert list(parse_hbc_bytecode(r.function_headers[23571],r))[0].inst.name!='GetEnvironment'
feedback=list(parse_hbc_bytecode(r.function_headers[23454],r))
assert [i.inst.name for i in feedback[:2]]==['LoadConstNull','Ret']

def execute(value):
    parent={0:lambda n:'icon'+str(n),1:list(range(10))};env={0:value}
    regs={};instructions=list(parse_hbc_bytecode(r.function_headers[23510],r))
    offsets={i.original_pos:n for n,i in enumerate(instructions)};pc=0
    while pc<len(instructions):
        i=instructions[pc];name=i.inst.name
        a=[getattr(i,'arg'+str(n)) for n in range(1,8) if hasattr(i,'arg'+str(n))];pc+=1
        if name=='GetEnvironment':regs[a[0]]=parent if a[1]==1 else env
        elif name=='LoadFromEnvironment':regs[a[0]]=regs[a[1]][a[2]]
        elif name in ('LoadConstNull','LoadConstUndefined'):regs[a[0]]=None
        elif name=='LoadConstUInt8':regs[a[0]]=a[1]
        elif name=='LoadConstString':regs[a[0]]=r.strings[a[1]]
        elif name=='GetByVal':regs[a[0]]=regs[a[1]][regs[a[2]]]
        elif name=='GetById':regs[a[0]]=regs[a[1]].get(r.strings[a[3]])
        elif name=='Call2':regs[a[0]]=regs[a[1]](regs[a[3]])
        elif name in ('JEqual','JNotEqual'):
            equal=regs[a[1]]==regs[a[2]]
            if equal==(name=='JEqual'):pc=offsets[i.original_pos+a[0]]
        elif name=='NewObject':regs[a[0]]={}
        elif name=='NewArray':regs[a[0]]=[None]*a[1]
        elif name=='PutNewOwnById':
            key=r.strings[a[2]];assert key not in regs[a[0]];regs[a[0]][key]=regs[a[1]]
        elif name=='PutOwnByIndex':regs[a[0]][a[2]]=regs[a[1]]
        elif name=='Ret':return regs[a[0]]
        else:raise AssertionError(name)
    raise AssertionError('No return')

values={'antiBother':{'todayCnt':0,'historyCnt':2,'unit':'次'},'antiOccupy':{'todayCnt':3,'historyCnt':5,'unit':'次'},'antiShake':{'todayCnt':None,'historyCnt':None,'unit':'次'}}
rows=execute(values)
assert [x['todayCnt'] for x in rows]==[0,3,'--']
assert [x['totalCnt'] for x in rows]==[2,5,'--']
assert all(x['todayCnt']=='--' for x in execute(None))
print('Stats bytecode: real zero/count/missing values correct; Setting preserved; residual feedback disabled')
