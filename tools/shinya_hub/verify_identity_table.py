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
        elif n=='Add':regs[a[0]]=regs[a[1]]+regs[a[2]]
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
bundle=load(Path(sys.argv[1]).read_bytes())
assert bundle.strings[20357]=='飞牛', '飞牛厂商名称被其他字符串覆盖'
assert bundle.strings[344]=='小星助手 V2.0', '助手标题与厂商名称必须独立'
header = list(parse_hbc_bytecode(bundle.function_headers[35695], bundle))
assert any(i.inst.name == 'LoadConstString' and i.arg2 == 344 for i in header), '助手聊天页未引用专用标题'
assert not any(i.inst.name == 'LoadConstString' and i.arg2 == 20357 for i in header), '助手聊天页仍引用飞牛厂商名称'
from patch_device_catalog import BRANDS, TYPES
brand_keys={key for key,_ in BRANDS};type_keys={key for key,_ in TYPES}
for key in ('ManufacturerIcons','StaTypeIcons','ChildStaTypeIcons'):
    exceptions=brand_keys if key=='ManufacturerIcons' else {'lock'}
    assert all(updated[key].get(k)==v for k,v in base[key].items() if k not in exceptions),key
for key in brand_keys:
    assert updated['ManufacturerIcons'][key]=={'uri':'asset:///hub-brandicons/'+key+'.png'}
for key in type_keys:
    for table_name in ('StaTypeIcons','ChildStaTypeIcons'):
        assert updated[table_name][key]=={'uri':'asset:///hub-brandicons/device_'+key+'.png'}
from verify_assistant_cards import VM
vm=VM(bundle)
locale={'manufacture':{'existing':'existing'}, 'staType':{'existing':'existing'}}
vm.run(23580,vm.module,[locale])
for key,title in BRANDS: assert locale['manufacture'][key]==title,(key,locale['manufacture'].get(key))
for key,title in TYPES: assert locale['staType'][key]==title,(key,locale['staType'].get(key))
assert locale['manufacture']['fnos']=='飞牛'
assert locale['staType']['existing']=='existing'
assert set(updated['ManufacturerIcons'])-set(base['ManufacturerIcons'])==({'ugreen','fnos'} | (brand_keys-set(base['ManufacturerIcons'])))
assert set(updated['StaTypeIcons'])-set(base['StaTypeIcons'])==({'nas'} | (type_keys-set(base['StaTypeIcons'])))
assert set(updated['ChildStaTypeIcons'])-set(base['ChildStaTypeIcons'])==({'nas','nas_default'} | (type_keys-set(base['ChildStaTypeIcons'])))
print('Shared icon tables verified; existing entries retained except requested brand assets')
