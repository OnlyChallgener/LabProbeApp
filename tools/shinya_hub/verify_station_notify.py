"""Execute notification eligibility, original row and native callback bytecode."""
import argparse
from pathlib import Path
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.serialized_literal_parser import unpack_slp_array,TagType
from hbc_grow import assert_bodies_mapped
from patch_bundle import load
from patch_station_notify import STRINGS,FIDS
from verify_terminal_ipv6_patch import check_read_caches


def literal(reader,buffer,offset,length):
    return [reader.strings[x.value] if x.tag_type in {TagType.LongStringTag,TagType.ShortStringTag,TagType.ByteStringTag}
            else x.value for x in unpack_slp_array(buffer[offset:],length,reader.header.version).items]


class VM:
    def __init__(self,reader,fid,env):
        self.reader=reader;self.r={};self.env=env;self.pc=0;self.stop=None
        self.code={i.original_pos:i for i in parse_hbc_bytecode(reader.function_headers[fid],reader)}

    def run(self):
        for _ in range(2000):
            if self.pc==self.stop:return
            i=self.code[self.pc];a=[getattr(i,'arg'+str(k+1)) for k in range(len(i.inst.operands))]
            n=i.inst.name;r=self.r;pc=self.pc;nxt=pc+1+len(bytes(i.inst.structure()))
            if n=='LoadParam':r[a[0]]=self.params[a[1]-1]
            elif n=='LoadConstNull' or n=='LoadConstUndefined':r[a[0]]=None
            elif n=='LoadConstString':r[a[0]]=self.reader.strings[a[1]]
            elif n=='GetEnvironment':r[a[0]]=self.env[a[1]]
            elif n=='LoadFromEnvironment':r[a[0]]=r[a[1]][a[2]]
            elif n in ('GetById','GetByIdShort'):r[a[0]]=r[a[1]].get(self.reader.strings[a[3]])
            elif n=='GetByVal':r[a[0]]=r[a[1]][r[a[2]]]
            elif n=='TypeOf':r[a[0]]='string' if isinstance(r[a[1]],str) else ('undefined' if r[a[1]] is None else 'object')
            elif n=='NewObject':r[a[0]]={}
            elif n in ('NewObjectWithBuffer','NewObjectWithBufferLong'):
                keys=literal(self.reader,self.reader.object_keys,a[3],a[2])
                vals=literal(self.reader,self.reader.object_values,a[4],a[2]);r[a[0]]=dict(zip(keys,vals))
            elif n=='NewArray':r[a[0]]=[None]*a[1]
            elif n=='PutOwnByIndex':
                while len(r[a[0]])<=a[2]:r[a[0]].append(None)
                r[a[0]][a[2]]=r[a[1]]
            elif n in ('PutNewOwnById','PutNewOwnByIdShort'):r[a[0]][self.reader.strings[a[2]]]=r[a[1]]
            elif n=='Mov':r[a[0]]=r[a[1]]
            elif n.startswith('Call'):r[a[0]]=r[a[1]](*[r[v] for v in a[3:]])
            elif n in ('Jmp','JmpLong'):nxt=pc+a[0]
            elif n in ('JmpFalse','JmpFalseLong','JmpTrue','JmpTrueLong'):
                if ('False' in n) != bool(r[a[1]]):nxt=pc+a[0]
            elif n in ('JEqualLong','JEqual','JStrictEqualLong','JStrictEqual','JStrictNotEqualLong'):
                x,y=r[a[1]],r[a[2]]
                equal=x is y if isinstance(x,(dict,list)) else type(x)==type(y) and x==y
                if ('NotEqual' in n) != equal:nxt=pc+a[0]
            elif n=='Ret':return r[a[0]]
            else:raise AssertionError((hex(pc),n))
            self.pc=nxt
        raise AssertionError('VM did not settle')


def check(reader):
    for sid,text in STRINGS.items():assert reader.strings[sid]==text,(sid,'missing string allocation')
    assert_bodies_mapped(reader)
    for fid in FIDS:
        check_read_caches(reader,fid)
        h=reader.function_headers[fid];assert not h.hasExceptionHandler
        instructions=list(parse_hbc_bytecode(h,reader));positions={i.original_pos for i in instructions}
        maxreg=-1;maxargs=0
        for i in instructions:
            for k,o in enumerate(i.inst.operands):
                if o.operand_type.name.startswith('Reg'):maxreg=max(maxreg,getattr(i,'arg'+str(k+1)))
            if i.inst.name.startswith('Call') and i.inst.name[4:].isdigit():maxargs=max(maxargs,int(i.inst.name[4:]))
            if i.inst.operands and 'Addr' in i.inst.operands[0].operand_type.name:assert i.original_pos+i.arg1 in positions
        assert maxreg+6+maxargs<h.frameSize,(fid,maxreg,maxargs,h.frameSize)
    cases=0
    base={'type':'phone','typeLevel2':'','projectId':'P-A','sn':'R-A','mac':'aa:bb:cc:dd:ee:ff'}
    def run(row,route=None):
        nonlocal cases
        route=base if route is None else route;calls=[];history=[]
        native={'notifyRemind':lambda *args:calls.append(args)}
        component={1:route,2:row,9:{'popoverItemText':'originalTextStyle'},40:lambda value:history.append(value)}
        module={5:{'NativeModules':{'sta':native}}}
        vm=VM(reader,33364,{0:component,1:module});vm.params=['stationNotify'];vm.run()
        # Execute just the actual appended row instructions inside the existing
        # component with original dependency identities and captured station.
        def jsx(kind,props,*key):return {'kind':kind,'props':props}
        menu=VM(reader,33301,{})
        start=next(i.original_pos for i in menu.code.values() if i.inst.name=='LoadFromEnvironment' and (i.arg1,i.arg2,i.arg3)==(100,8,2))
        menu.pc=start
        menu.stop=next(i.original_pos for i in menu.code.values() if i.original_pos>start and i.inst.name=='GetByIdShort' and (i.arg1,i.arg2,i.arg4)==(38,23,26))
        menu.r={0:{9:{'jsx':jsx,'jsxs':jsx},27:{'Popover':{'Item':'OriginalPopoverItem'},'Flex':'OriginalFlex','Icon':'OriginalIcon'}},
                3:None,5:{5:{'Text':'OriginalText'}},8:component,9:9,27:27,40:['original-history-row'],46:lambda dependency:dependency}
        menu.run();cases+=1
        return calls,history,menu.r[40]
    for field,value in (('type','phone'),('type','pc'),('type','pad'),('typeLevel2','phone'),('typeLevel2','pc'),('typeLevel2','pad')):
        row=dict(base);row[field]=value;calls,history,items=run(row)
        assert calls==[('P-A','R-A',base['mac'])] and not history
        assert len(items)==2 and items[0]=='original-history-row'
        node=items[1];assert node['kind']=='OriginalPopoverItem' and node['props']['value']=='stationNotify'
        children=node['props']['children']['props']['children']
        assert children[0]['kind']=='OriginalIcon'
        assert children[1]=={'kind':'OriginalText','props':{'style':'originalTextStyle','children':'通知提醒'}}
    for subtype in ('huawei','unknown','mobile','tablet','laptop'):
        calls,history,items=run(dict(base,type='phone',typeLevel2=subtype));assert len(items)==2 and len(calls)==1
    for subtype in ('nas','camera','printer','other','tv','watch','router','appliances','sound','network','game',0,False,[],{}):
        calls,history,items=run(dict(base,type='pc',typeLevel2=subtype));assert not calls and len(items)==1
    for kind in ('nas','camera','other','unknown','tv','watch',None,''):
        calls,history,items=run(dict(base,type=kind));assert not calls and len(items)==1
    for field in ('projectId','sn','mac'):
        calls,history,items=run(dict(base,**{field:''}));assert not calls and len(items)==1
        calls,history,items=run(dict(base,**{field:123}));assert not calls and len(items)==1
    for field,value in (('mac','11:22:33:44:55:66'),('projectId','P-B'),('sn','R-B')):
        calls,history,items=run(base,dict(base,**{field:value}));assert not calls and len(items)==1
    calls,history,items=run(None);assert not calls and len(items)==1
    # History selection still invokes precisely the original env40 callback.
    seen=[];vm=VM(reader,33364,{0:{40:lambda value:seen.append(value)}});vm.params=['history'];vm.run();assert seen==['history']
    return cases+1


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('bundle',type=Path)
    p.add_argument('--unallocated',action='store_true',help='local body review only; inject labels into reader, not a distributable bundle')
    args=p.parse_args();r=load(args.bundle.read_bytes())
    if args.unallocated:
        for sid,text in STRINGS.items():r.strings[sid]=text
    print('PASS:',check(r),'eligibility/scope/row/native cases; frame/cache/branches', '(unallocated body review)' if args.unallocated else '')
