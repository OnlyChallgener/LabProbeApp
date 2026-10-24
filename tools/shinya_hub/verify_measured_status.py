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
        elif n=='StrictNeq':r[a[0]]=r[a[1]]!=r[a[2]]
        elif n=='JEqual':
            if r[a[1]]==r[a[2]]:next_pc=pc+a[0]
        elif n=='JNotEqual':
            if r[a[1]]!=r[a[2]]:next_pc=pc+a[0]
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
    patched=load(args.bundle.read_bytes() if args.bundle else bytes(data))
    assert changed==set(FUNCTIONS)
    for fid in FUNCTIONS:
        check_frame(patched,fid);check_read_caches(patched,fid)
        before,after=r.function_headers[fid],patched.function_headers[fid]
        insertions,replacements=plans(r)[fid]
        expected,mapping,_=rewrite(r,fid,insertions,replacements)
        patched.file_buffer.seek(after.offset)
        assert patched.file_buffer.read(after.bytecodeSizeInBytes)==expected
        assert after.frameSize>=before.frameSize and before.environmentSize==after.environmentSize
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
        else:
            sites = ((0xb5,0xcf,0,'predictCrossWall'),(0x253,0x26d,19,'predictCover'),(0x294,0x2ae,19,'predictRoomType')) if fid==24165 else (
                     (0x522,0x534,33,'predictCover'),(0x750,0x762,37,'predictRoomType'),(0x964,0x976,34,'predictCrossWall'))
            for start,stop,out,field in sites:
                for value in (None,150,'3室2厅',2):
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
    if not args.bundle:
        # Every old body is still physically present byte-for-byte, including
        # signal terminal list callbacks, card styles, APIs, and closure IDs.
        for fid,h in enumerate(r.function_headers):
            assert data[h.offset:h.offset+h.bytecodeSizeInBytes]==original[h.offset:h.offset+h.bytecodeSizeInBytes],fid
    print('PASS: original score card null/missing/zero/real values; coverage missing/real fields and absent badge; branch/frame/cache capacity; original bodies retained')


if __name__=='__main__':main()

