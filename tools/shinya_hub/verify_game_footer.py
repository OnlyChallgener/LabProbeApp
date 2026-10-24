"""Execute the patched footer with strict object writes and React-like props."""
import copy,sys
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

r=load(Path(sys.argv[1]).read_bytes())
def element(kind,props,children=None):
 props=copy.copy(props)
 if children is not None:props['children']=children
 return {'type':kind,'props':props}
for repeat in range(4):
 props={'style':{'borderBottomLeftRadius':24,'borderBottomRightRadius':24},'colors':['#111A25','#222C39'],'useAngle':True,'angle':180}
 hero={'type':'hero','props':{}}
 regs={};parent={4:{'createElement':element},6:{n:n for n in ('View','TouchableOpacity','Text','Image')}}
 parameters={1:props,2:hero,3:'LinearGradient'}
 for i in parse_hbc_bytecode(r.function_headers[23472],r):
  n=i.inst.name;a=[getattr(i,'arg'+str(v)) for v in range(1,8) if hasattr(i,'arg'+str(v))]
  if n=='LoadParam':regs[a[0]]=parameters[a[1]]
  elif n=='GetEnvironment':regs[a[0]]=parent if a[1]==1 else {}
  elif n=='LoadFromEnvironment':regs[a[0]]=regs[a[1]][a[2]]
  elif n in ('GetById','GetByIdShort'):regs[a[0]]=regs[a[1]][r.strings[a[3]]]
  elif n=='LoadConstUndefined':regs[a[0]]=None
  elif n=='LoadConstUInt8':regs[a[0]]=a[1]
  elif n=='LoadConstString':regs[a[0]]=r.strings[a[1]]
  elif n=='NewObject':regs[a[0]]={}
  elif n=='NewArray':regs[a[0]]=[None]*a[1]
  elif n=='NewArrayWithBufferLong':
   assert a[3]==69004;regs[a[0]]=[.7998,1]
  elif n=='PutOwnByIndex':regs[a[0]][a[2]]=regs[a[1]]
  elif n in ('PutNewOwnById','PutNewOwnByIdShort'):
   key=r.strings[a[2]];assert key not in regs[a[0]],('new-own overwrites existing property',key)
   regs[a[0]][key]=regs[a[1]]
  elif n=='PutById':regs[a[0]][r.strings[a[3]]]=regs[a[1]]
  elif n=='CreateClosure':regs[a[0]]=a[2]
  elif n in ('Call3','Call4'):regs[a[0]]=regs[a[1]](*(regs[v] for v in a[3:]))
  elif n=='Ret':result=regs[a[0]];break
  else:raise AssertionError(n)
 assert result['type']=='LinearGradient'
 assert result['props']['locations']==[.7998,1]
 assert result['props']['children'][0] is hero
 row=result['props']['children'][1]
 assert row['type']=='View' and row['props']['style']=={'flexDirection':'row','position':'absolute','left':16,'right':16,'bottom':16}
 names=[button['props']['children'][1]['props']['children'] for button in row['props']['children']]
 assert names==['STUN穿透','UU加速','应用商店'],names
 assert result['props']['style'][1]['paddingBottom']==48
print('Footer props, gradient locations, original hero, and three text nodes verified; all own-property writes valid')
