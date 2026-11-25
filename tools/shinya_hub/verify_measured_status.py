"""Focused execution and structure checks for original measured-value views."""
import argparse
import hashlib
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from patch_bundle import load
from patch_measured_status import additions, plans, rewrite, FUNCTIONS
from verify_station_lookup import check_frame
from verify_terminal_ipv6_patch import check_read_caches


def execute(reader, fid, start, stop, registers):
    ins = list(parse_hbc_bytecode(reader.function_headers[fid], reader))
    table = {i.original_pos:i for i in ins}; pc=start; r=dict(registers)
    for _ in range(100):
        if pc==stop:return r
        i=table[pc]; a=[getattr(i,'arg'+str(n+1)) for n in range(len(i.inst.operands))]
        next_pc=pc+1+len(bytes(i.inst.structure())); n=i.inst.name
        if n=='LoadFromEnvironment':r[a[0]]=r[a[1]].get(a[2])
        elif n in ('LoadConstUndefined','LoadConstNull'):r[a[0]]=None
        elif n=='LoadConstString':r[a[0]]=reader.strings[a[1]]
        elif n in ('GetById','GetByIdShort'):r[a[0]]=r[a[1]].get(reader.strings[a[3]])
        elif n=='Mov':r[a[0]]=r[a[1]]
        elif n=='NewObject':r[a[0]]={}
        elif n=='GetEnvironment':r[a[0]]=r['_env'][a[1]]
        elif n=='GetGlobalObject':r[a[0]]=r['_globals']
        elif n=='LoadConstInt':r[a[0]]=a[1]
        elif n=='Add':r[a[0]]=r[a[1]]+r[a[2]]
        elif n=='GetByVal':r[a[0]]=r[a[1]].get(r[a[2]])
        elif n=='PutByVal':r[a[0]][r[a[1]]]=r[a[2]]
        elif n=='PutNewOwnById':r[a[0]][reader.strings[a[2]]]=r[a[1]]
        elif n=='PutOwnByIndex':r[a[0]][a[2]]=r[a[1]]
        elif n=='StoreToEnvironment':r[a[0]][a[1]]=r[a[2]]
        elif n in ('Call2','Call3'):r[a[0]]=r[a[1]](r[a[2]],*[r[x] for x in a[3:]])
        elif n=='StrictNeq':r[a[0]]=r[a[1]]!=r[a[2]]
        elif n=='JEqual':
            if r[a[1]]==r[a[2]]:next_pc=pc+a[0]
        elif n=='JNotEqual':
            if r[a[1]]!=r[a[2]]:next_pc=pc+a[0]
        elif n.startswith('JmpFalse'):
            if not r[a[1]]:next_pc=pc+a[0]
        elif n.startswith('JmpTrue'):
            if r[a[1]]:next_pc=pc+a[0]
        else:raise AssertionError((fid,hex(pc),n))
        pc=next_pc
    raise AssertionError('segment did not finish')


def main():
    p=argparse.ArgumentParser();p.add_argument('--bundle',type=Path);args=p.parse_args()
    source=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.android.bundle'
    original=source.read_bytes();r=load(original);changed=set();changes=[]
    data=additions(r,bytearray(original),changed,changes)
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    isolated=load(bytes(data))
    patched=load(args.bundle.read_bytes() if args.bundle else bytes(data))
    assert changed==set(FUNCTIONS)
    for fid in FUNCTIONS:
        check_frame(patched,fid);check_read_caches(patched,fid)
        before,after=r.function_headers[fid],patched.function_headers[fid]
        insertions,replacements=plans(r)[fid]
        expected,mapping,_=rewrite(r,fid,insertions,replacements)
        if args.bundle:
            # The combined bundle assigns cache slots in first-property-use
            # order after all patches. Derive that exact permitted byte rewrite
            # from the isolated production patch, never from the actual bundle.
            # Compare every remaining byte (including all watcher instructions,
            # property IDs, registers and branches) without exceptions.
            normalized=bytearray(expected);slots={}
            read_ops={'GetByIdShort','GetById','GetByIdLong','TryGetById','TryGetByIdLong'}
            for instruction in parse_hbc_bytecode(isolated.function_headers[fid],isolated):
                if instruction.inst.name not in read_ops:continue
                sid=instruction.arg4
                if sid not in slots:slots[sid]=len(slots)+1
                normalized[instruction.original_pos+3]=slots[sid]
            expected=bytes(normalized)
        patched.file_buffer.seek(after.offset)
        assert patched.file_buffer.read(after.bytecodeSizeInBytes)==expected,('measured body differs beyond final cache normalization',fid)
        assert after.frameSize>=before.frameSize
        assert after.environmentSize==before.environmentSize+(1 if fid==33059 else 2 if fid==24164 else 0)
        decoded=list(parse_hbc_bytecode(after,patched));positions={i.original_pos for i in decoded}
        for i in decoded:
            if i.inst.operands and 'Addr' in i.inst.operands[0].operand_type.name:
                assert i.original_pos+i.arg1 in positions
        # Exercise the actual optional field read and display fallback bytecode.
        if fid==33242:
            for value,want in ((None,'--'),(0,0),(78,78),(100,100)):
                regs={9:{3:{'overallScore':value}},28:None}
                assert execute(patched,fid,mapping[0x428],mapping[0x43e],regs)[27]==want
            assert execute(patched,fid,mapping[0x428],mapping[0x43e],{9:{3:{}},28:None})[27]=='--'
        elif fid in (24165,33059):
            sites = ((0xb5,0xcf,0,'predictCrossWall'),(0x253,0x26d,19,'predictCover'),(0x294,0x2ae,19,'predictRoomType')) if fid==24165 else (
                     (0x522,0x534,33,'predictCover'),(0x750,0x762,37,'predictRoomType'),(0x964,0x976,34,'predictCrossWall'))
            for start,stop,out,field in sites:
                for value in (None,0,150,'3室2厅',2):
                    payload={field:value}
                    regs={1:{3:payload},25:payload if fid==33059 else None,28:None,20:2,27:120,31:'3室1厅',40:120,32:'3室1厅',38:'2'}
                    want='--' if value is None else value
                    assert execute(patched,fid,mapping[start],mapping[stop],regs)[out]==want,(fid,field,value)
                payload={}
                regs={1:{3:payload},25:payload if fid==33059 else None,28:None,20:2,27:120,31:'3室1厅',40:120,32:'3室1厅',38:'2'}
                assert execute(patched,fid,mapping[start],mapping[stop],regs)[out]=='--'
            if fid==24165:
                for value,want in ((None,False),('??',False),(2,True)):
                    assert execute(patched,fid,mapping[0x43],mapping[0x61],{1:{3:{'predictCrossWall':value}}})[7]==want
    # Execute the inserted production watcher, including the actual original
    # refetch function identity and view-registration cache. Its zero dimensions
    # leave every original Text, style and topology node untouched.
    calls=[];registered=[];glob={};parent={};refetch=lambda *_:calls.append('coverage-refetch')
    _,mapping,_=rewrite(r,24164,*plans(r)[24164])
    stop=mapping[0x60]+len(plans(r)[24164][0][0x60])
    # The real hook returns identity separately from measurement data.
    hook={'refetchCoverData':refetch,'coverData':{'predictCover':120,'predictRoomType':'2房2厅','predictCrossWall':3},'projectId':'canonical-A','sn':'SN-A'}
    execute(patched,24164,mapping[0x60],stop,{0:hook,10:parent})
    # Execute original late stores: slot5 is the render callback and MUST NOT
    # own refetch. The old segment-only mock failed to exercise this overwrite.
    render=lambda *_:None
    execute(patched,24164,mapping[0x332],mapping[0x33a],{10:parent,19:render,13:'second-card'})
    assert parent[5] is render and parent[9] is refetch and parent[8] is hook
    deps=execute(patched,24164,mapping[0x14c],mapping[0x14c]+len(plans(r)[24164][0][0x14c]),{10:parent,1:{}})[1]
    assert deps=={13:'canonical-A',14:'SN-A',15:refetch},'memoized card must refresh when project/AP/refetch changes even if measurements are identical'
    _,mapping,_=rewrite(r,24165,*plans(r)[24165])
    start=mapping[0x443];stop=start+len(plans(r)[24165][0][0x443])
    def require(_,name):registered.append(name);return name
    module={3:{'requireNativeComponent':require}}
    parent[3]=hook['coverData']  # deliberately has no projectId/sn
    for _ in range(3):
        regs={0:{0:'badge',1:'original-card'},1:'original-card',3:lambda _,kind,props:{'kind':kind,'props':props},4:None,'_env':[parent,module],'_globals':glob}
        result=execute(patched,24165,start,stop,regs)
        assert result[1]=='original-card' and result[0][1]=='original-card'
        view=result[0][2];assert view['kind']=='Hubcoverage'
        assert view['props']['style']=={'width':0,'height':0}
        assert view['props']['type']=='watch'
        assert view['props']['projectId']=='canonical-A' and view['props']['sn']=='SN-A'
        view['props']['onChange'](None)
    assert registered==['Hubcoverage'] and calls==['coverage-refetch']*3
    # Actual selector identity capture/export; never confuse coverData with AP.
    _,mapping,_=rewrite(r,24179,*plans(r)[24179])
    for project,ap in (({'projectId':'canonical-A'},{'sn':'SN-A'}),(None,None)):
        capture=execute(patched,24179,mapping[0x39],mapping[0x39]+len(plans(r)[24179][0][0x39]),{2:project,12:ap})
        capture[0]={}
        out=execute(patched,24179,mapping[0x319],mapping[0x319]+len(plans(r)[24179][0][0x319]),capture)[0]
        assert out=={'projectId':project['projectId'] if project else None,'sn':ap['sn'] if ap else None}
    if not args.bundle:
        # Every old body is still physically present byte-for-byte, including
        # signal terminal list callbacks, card styles, APIs, and closure IDs.
        for fid,h in enumerate(r.function_headers):
            assert data[h.offset:h.offset+h.bytecodeSizeInBytes]==original[h.offset:h.offset+h.bytecodeSizeInBytes],fid
    print('PASS: original score card null/missing/zero/real values; coverage missing/real fields and absent badge; native watcher invokes only coverage refetch and retains original card; branch/frame/cache capacity; original bodies retained')


if __name__=='__main__':main()

