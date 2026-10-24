"""Evaluate all three added line-icon assets through their shipped renderer."""
import sys,zipfile
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

r=load(Path(sys.argv[1]).read_bytes())
def render(name):
    instructions=list(parse_hbc_bytecode(r.function_headers[20053],r));positions={x.original_pos:i for i,x in enumerate(instructions)}
    regs={};pc=0
    def require(n):
        if n==18:return {'Image':'Image'}
        if n==176:return {'jsx':lambda kind,props:(kind,props)}
        raise AssertionError(n)
    while pc<len(instructions):
        x=instructions[pc];pc+=1;n=x.inst.name;a=[getattr(x,'arg'+str(i)) for i in range(1,6) if hasattr(x,'arg'+str(i))]
        if n=='LoadParam':regs[a[0]]={'name':name}
        elif n=='LoadConstString':regs[a[0]]=r.strings[a[1]]
        elif n=='LoadConstUndefined':regs[a[0]]=None
        elif n=='LoadConstUInt8':regs[a[0]]=a[1]
        elif n=='GetGlobalObject':regs[a[0]]={'__r':require}
        elif n in ('GetById','GetByIdShort'):regs[a[0]]=regs[a[1]][r.strings[a[3]]]
        elif n in ('JStrictEqual','JStrictNotEqual'):
            equal=regs[a[1]]==regs[a[2]]
            if equal==(n=='JStrictEqual'):pc=positions[x.original_pos+a[0]]
        elif n=='Call2':regs[a[0]]=regs[a[1]](regs[a[3]])
        elif n=='Call3':regs[a[0]]=regs[a[1]](regs[a[3]],regs[a[4]])
        elif n=='NewObject':regs[a[0]]={}
        elif n in ('PutNewOwnById','PutNewOwnByIdShort'):regs[a[0]][r.strings[a[2]]]=regs[a[1]]
        elif n=='Ret':return regs[a[0]]
        else:raise AssertionError(n)
for name,asset in [('DDNS','ddns.png'),('防火墙','firewall.png'),('内网穿透','intranet-penetration.png')]:
    kind,props=render(name)
    assert kind=='Image' and props['source']['uri']=='asset:///hub-ui/'+asset,(name,props)
    assert props['style']=={'width':24,'height':24}
    if len(sys.argv)>2:
        with zipfile.ZipFile(sys.argv[2]) as apk:assert apk.read('assets/hub-ui/'+asset).startswith(b'\x89PNG')
print('DDNS, firewall and intranet icons select the correct packaged PNG at 24 dp')

# The added common-tools item uses a different renderer from router cards.
# Execute its append tail: checking IconFont alone missed the blank tile.
menu=list(parse_hbc_bytecode(r.function_headers[23455],r))
literal=next(i for i,x in enumerate(menu) if x.inst.name=='LoadConstString' and r.strings[x.arg2]=='内网穿透')
tail=menu[literal-1:];regs={0:[None]*7}
for x in tail:
    n=x.inst.name;a=[getattr(x,'arg'+str(i)) for i in range(1,6) if hasattr(x,'arg'+str(i))]
    if n=='NewObject':regs[a[0]]={}
    elif n=='LoadConstString':regs[a[0]]=r.strings[a[1]]
    elif n in ('PutNewOwnById','PutNewOwnByIdShort'):regs[a[0]][r.strings[a[2]]]=regs[a[1]]
    elif n=='PutOwnByIndex':regs[a[0]][a[2]]=regs[a[1]]
    elif n=='Ret':break
    else:raise AssertionError(n)
item=regs[0][6]
assert item['type']==item['label']=='内网穿透'
assert item['icon']=={'uri':'asset:///hub-ui/intranet-penetration.png'},item
print('Common-tools penetration tile carries its image source')
