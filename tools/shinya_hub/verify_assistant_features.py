"""Execute new More/selection/footer bytecode and preserve original button UI."""
import argparse
import hashlib
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hbc_grow import assert_bodies_mapped
from patch_assistant_features import additions, OPEN, SELECTED, RANK_NAME, DAILY_NAME
from patch_bundle import load
from capture_assistant_typography import literal
from verify_station_lookup import check_frame
from verify_terminal_ipv6_patch import check_read_caches, Env, Fn


class VM:
    def __init__(self,reader):self.reader=reader
    def prop(self,obj,key):
        if isinstance(obj,dict):return obj.get(key)
        if isinstance(obj,list):return len(obj) if key=='length' else obj[key] if isinstance(key,int) and 0<=key<len(obj) else None
        return None
    def run(self,fid,env,args=()):
        instructions=list(parse_hbc_bytecode(self.reader.function_headers[fid],self.reader));bypos={i.original_pos:n for n,i in enumerate(instructions)}
        r={};pc=0
        for _ in range(3000):
            ins=instructions[pc];pc+=1;n=ins.inst.name;a=[getattr(ins,'arg'+str(k+1)) for k in range(len(ins.inst.operands))]
            if n=='GetEnvironment':
                e=env
                for _ in range(a[1]):e=e.parent
                r[a[0]]=e
            elif n=='CreateEnvironment':r[a[0]]=Env(env)
            elif n=='LoadFromEnvironment':r[a[0]]=r[a[1]].slots.get(a[2])
            elif n=='StoreToEnvironment':r[a[0]].slots[a[1]]=r[a[2]]
            elif n=='LoadParam':r[a[0]]=args[a[1]-1] if len(args)>=a[1] else None
            elif n in ('LoadConstNull','LoadConstUndefined'):r[a[0]]=None
            elif n=='LoadConstTrue':r[a[0]]=True
            elif n=='LoadConstFalse':r[a[0]]=False
            elif n=='LoadConstZero':r[a[0]]=0
            elif n in ('LoadConstUInt8','LoadConstInt'):r[a[0]]=a[1]
            elif n=='LoadConstString':r[a[0]]=self.reader.strings[a[1]]
            elif n in ('GetById','GetByIdShort'):r[a[0]]=self.prop(r[a[1]],self.reader.strings[a[3]])
            elif n=='GetByVal':r[a[0]]=self.prop(r[a[1]],r[a[2]])
            elif n in ('PutNewOwnById','PutNewOwnByIdShort'):r[a[0]][self.reader.strings[a[2]]]=r[a[1]]
            elif n=='NewObject':r[a[0]]={}
            elif n in ('NewObjectWithBuffer','NewObjectWithBufferLong'):
                r[a[0]]=dict(zip(literal(self.reader,self.reader.object_keys[a[3]:],a[2]),literal(self.reader,self.reader.object_values[a[4]:],a[2])))
            elif n=='NewArray':r[a[0]]=[None]*a[1]
            elif n=='PutOwnByIndex':r[a[0]][a[2]]=r[a[1]]
            elif n=='CreateClosure':
                e,f=r[a[1]],a[2];r[a[0]]=Fn(lambda _,*values,e=e,f=f:self.run(f,e,values))
            elif n.startswith('Call'):r[a[0]]=r[a[1]].call(r[a[2]],[r[k] for k in a[3:]])
            elif n=='Mov':r[a[0]]=r[a[1]]
            elif n=='Greater':r[a[0]]=r[a[1]]>r[a[2]]
            elif n=='StrictEq':r[a[0]]=type(r[a[1]])==type(r[a[2]]) and r[a[1]]==r[a[2]]
            elif n=='TypeOf':
                v=r[a[1]];r[a[0]]='string' if isinstance(v,str) else 'undefined' if v is None else 'object'
            elif n.startswith('J'):
                if n in ('Jmp','JmpLong'):yes=True
                elif n in ('JmpFalse','JmpFalseLong'):yes=not r[a[1]]
                elif n in ('JmpTrue','JmpTrueLong'):yes=bool(r[a[1]])
                elif n in ('JStrictEqual','JStrictEqualLong'):yes=type(r[a[1]])==type(r[a[2]]) and r[a[1]]==r[a[2]]
                elif n in ('JStrictNotEqual','JStrictNotEqualLong'):yes=type(r[a[1]])!=type(r[a[2]]) or r[a[1]]!=r[a[2]]
                else:raise AssertionError((fid,n))
                if yes:pc=bypos[ins.original_pos+a[0]]
            elif n=='Ret':return r[a[0]]
            else:raise AssertionError((fid,n))
        raise AssertionError('did not settle')


def walk(node):
    if isinstance(node,dict):
        yield node;yield from walk(node.get('props',{}).get('children'))
    elif isinstance(node,list):
        for value in node:yield from walk(value)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--bundle',type=Path);args=parser.parse_args()
    source=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle'
    original=source.read_bytes();before=load(original)
    if args.bundle:after=load(args.bundle.read_bytes())
    else:
        data=additions(before,bytearray(original),set(),[]);data[-20:]=hashlib.sha1(data[:-20]).digest();after=load(bytes(data))
        # Main allocator owns these shared labels. In isolated execution only,
        # assign their resolved values to the parser's model, not artifact bytes.
        after.strings[RANK_NAME]='网络排行';after.strings[DAILY_NAME]='网络日报'
    for fid in (OPEN,SELECTED,35770,35766):check_frame(after,fid);check_read_caches(after,fid)
    assert_bodies_mapped(after)
    vm=VM(after);calls=[];opened=[]
    module=Env();helper=Env(module)
    def jsx(_,kind,props,key=None):return {'kind':kind,'props':props,'key':key}
    require=Fn(lambda _,which:{'jsx':Fn(jsx),'jsxs':Fn(jsx)} if which==43 else {'Button':'Button','Flex':'Flex'})
    module.slots.update({0:require,1:list(range(44)),6:{'Text':'Text','NativeModules':{'sta':{'more':Fn(lambda _,project,callback:opened.append((project,callback)))}}},
        12:{'default':'Icon'},28:{'diagnose_loading':'diagnose','clearHistory':'ic-close','share':'ic-share','appUpdate':'appUpdate'}})
    styles={'actionButton':{'originalButton':True},'actionButtonMargin':{'originalMargin':True},'actionButtonText':{'fontSize':14}}
    destinations=[]
    helper.slots.update({5:styles,19:{'navigate':Fn(lambda _,route:destinations.append(route))},23:'BE50-project',33:Fn(lambda _,question,kind:calls.append((question,kind)))})
    vm.run(OPEN,helper,[{'name':'更多功能','type':'more'}])
    assert destinations==['Tool'] and not calls and not opened, 'More must retain original Tool navigation without opening a new directory'
    items=vm.run(35770,Env())
    assert [item['type'] for item in items[:4]]==['diagnose_loading','clearHistory','share','appUpdate']
    assert [item['name'] for item in items[-2:]]==['网络排行','网络日报']
    assert all(item['type']!='customer' for item in items)
    rendered=[vm.run(35766,helper,[item,index]) for index,item in enumerate(items)]
    assert [r['key'] for r in rendered]==['diagnose_loading','clearHistory','share','appUpdate','bandwidth','daily']
    assert all(any(n['kind']=='Icon' for n in walk(r)) for r in rendered[:4])
    assert all(not any(n['kind']=='Icon' for n in walk(r)) for r in rendered[-2:])
    assert all(next(n for n in walk(r) if n['kind']=='Text')['props']['style'][0] is styles['actionButtonText'] for r in rendered)
    for item in items[-2:]:vm.run(OPEN,helper,[item])
    assert calls[-2:]==[('网络排行','chat'),('网络日报','chat')]
    # The rewritten more branch delegates to OPEN. This remains detectable if
    # history subsequently inserts/rebases its clear-history code.
    assert any(i.inst.name=='CreateClosure' and i.arg3==OPEN for i in parse_hbc_bytecode(after.function_headers[35728],after))
    print('PASS: original More to Tool navigation; original four pills/styles/icons, two unique-key additions; frame/cache/code-region checks')


if __name__=='__main__':main()
