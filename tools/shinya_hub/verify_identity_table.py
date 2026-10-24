"""Execute the straight-line icon table with fake, checked Metro asset imports."""
import sys
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

def table(path):
    r=load(path.read_bytes());regs={};exports={};undefined=object()
    def require(index):
        assert isinstance(index,int) and 0<=index<152,('invalid asset import',index)
        return {'asset':index}
    def define(obj,key,value):obj[key]=value['value']
    params={2:require,6:exports,7:list(range(152))}
    for i in parse_hbc_bytecode(r.function_headers[22183],r):
        n=i.inst.name;a=[getattr(i,'arg'+str(v)) for v in range(1,8) if hasattr(i,'arg'+str(v))]
        if n=='Ret':break
        if n=='LoadParam':regs[a[0]]=params[a[1]]
        elif n=='GetGlobalObject':regs[a[0]]={'Object':{'defineProperty':define}}
        elif n in ('TryGetById','GetById','GetByIdShort'):regs[a[0]]=regs[a[1]][r.strings[a[3]]]
        elif n=='LoadConstTrue':regs[a[0]]=True
        elif n=='LoadConstUndefined':regs[a[0]]=undefined
        elif n=='LoadConstZero':regs[a[0]]=0
        elif n in ('LoadConstUInt8','LoadConstInt'):regs[a[0]]=a[1]
        elif n=='LoadConstString':regs[a[0]]=r.strings[a[1]]
        elif n=='NewObject':regs[a[0]]={}
        elif n=='GetByVal':regs[a[0]]=regs[a[1]][regs[a[2]]]
        elif n=='Mov':regs[a[0]]=regs[a[1]]
        elif n=='Call2':regs[a[0]]=regs[a[1]](regs[a[3]])
        elif n=='Call4':regs[a[0]]=regs[a[1]](*(regs[v] for v in a[3:]))
        elif n in ('PutNewOwnById','PutNewOwnByIdShort'):regs[a[0]][r.strings[a[2]]]=regs[a[1]]
        elif n=='PutById':regs[a[0]][r.strings[a[3]]]=regs[a[1]]
        elif n=='PutByVal':regs[a[0]][regs[a[1]]]=regs[a[2]]
        elif n=='PutOwnByIndexL':regs[a[0]][a[2]]=regs[a[1]]
        else:raise AssertionError(n)
    return exports

base=table(Path('test/_analysis/oct08-shinya-hub/index.android.bundle'))
updated=table(Path(sys.argv[1]))
for key in ('ManufacturerIcons','StaTypeIcons','ChildStaTypeIcons'):
    assert all(updated[key].get(k)==v for k,v in base[key].items()),key
assert set(updated['ManufacturerIcons'])-set(base['ManufacturerIcons'])=={'ugreen','fnos'}
assert set(updated['StaTypeIcons'])-set(base['StaTypeIcons'])=={'nas'}
assert set(updated['ChildStaTypeIcons'])-set(base['ChildStaTypeIcons'])=={'nas','nas_default'}
print('All original icon entries retained; only UGREEN/fnOS/NAS added; all asset imports valid')
