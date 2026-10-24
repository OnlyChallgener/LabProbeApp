"""Add icon choices to the exact official tables without replacing existing keys."""
import struct
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops

# Dedicated strings of the removed assistant and telemetry integration.
STRINGS = {7156:'ugreen',7163:'fnos',17308:'nas',
           19149:'asset:///hub-brandicons/ugreen.png',
           19600:'asset:///hub-brandicons/fnos.png',
           20355:'asset:///hub-brandicons/device_nas.png',
           20356:'绿联',20357:'飞牛',20359:'NAS',11656:'nas_default'}


def additions(reader, original, replace, edit_generator):
    h=reader.function_headers[22183]
    insts=list(parse_hbc_bytecode(h,reader))
    assert not any(i.inst.name.startswith('J') for i in insts)
    uri_sid=reader.strings.index('uri')
    def icon(key,uri,target=1):
        # Low registers retain dependency indices across the type/child tables.
        # 71/72 are dead after each completed object and are overwritten before
        # any subsequent use. Never clobber those retained index registers.
        return bytes([ops.NewObject.opcode,72,ops.LoadConstString.opcode,71])+struct.pack('<H',uri)+bytes([
            ops.PutNewOwnById.opcode,72,71])+struct.pack('<H',uri_sid)+bytes([
            ops.LoadConstString.opcode,71])+struct.pack('<H',key)+bytes([ops.PutByVal.opcode,target,71,72])
    inserts={0x4af:icon(7156,19149)+icon(7163,19600),
             0x8b9:icon(17308,20355,9),0xb8b:icon(17308,20355)+icon(11656,20355)}
    result=bytearray();skip=False;duplicates=0
    for n,i in enumerate(insts):
        if skip:skip=False;continue
        pos=i.original_pos
        # These exports are assigned their final objects below. There are no
        # cyclic asset dependencies needing the initial undefined properties.
        if 0x2b<=pos<0x3d:
            assert i.inst.name=='PutById'
            continue
        result+=inserts.get(pos,b'')
        if i.inst.name=='GetByVal' and i.arg2==47 and n>=2:
            previous=insts[n-1];lookup=insts[n-2];call=insts[n+1]
            if (previous.inst.name=='Call2' and lookup.inst.name=='GetByVal'
                and lookup.arg2==47 and lookup.arg3==i.arg3 and lookup.arg1!=lookup.arg3):
                assert call.inst.name=='Call2' and call.arg2==3 and call.arg3==0
                result+=bytes([ops.Mov.opcode,call.arg1,previous.arg1]);skip=True;duplicates+=1
                continue
        end=insts[n+1].original_pos if n+1<len(insts) else h.bytecodeSizeInBytes
        result+=original[h.offset+pos:h.offset+end]
    assert duplicates==14
    replace(22183,bytes(result),'add UGREEN/fnOS/NAS alongside all original icon choices')

    # The former upgrade callback's code is already retired. Keep it a no-op
    # for other callers, and use only objects with this exact locale shape.
    code=bytearray([ops.LoadParam.opcode,0,1,ops.LoadConstUndefined.opcode,3,
                    ops.JmpFalse.opcode,0,0])
    guards=[5]
    for reg,sid,cache in ((1,25031,1),(2,33957,2)):
        code+=bytes([ops.GetById.opcode,reg,0,cache])+struct.pack('<H',sid)
        guards.append(len(code));code+=bytes([ops.JmpFalse.opcode,0,reg])
    for reg,key,value in ((1,7156,20356),(1,7163,20357),(2,17308,20359)):
        code+=bytes([ops.LoadConstString.opcode,0])+struct.pack('<H',key)
        code+=bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',value)
        code+=bytes([ops.PutByVal.opcode,reg,0,4])
    end=len(code);code+=bytes([ops.Ret.opcode,3])
    for guard in guards:code[guard+1]=end-guard
    replace(23580,bytes(code),'append only new locale entries; retired update callback remains inactive')
    h=reader.function_headers[7933]
    # Babel's __esModule marker is sufficient for its default interop here.
    # Keep every original buffered translation entry and nested object.
    code=bytearray([ops.LoadParam.opcode,2,6,ops.LoadConstTrue.opcode,0,
                    ops.PutNewOwnByIdShort.opcode,2,0,46,ops.LoadConstUndefined.opcode,0])
    code+=original[h.offset+0x2b:h.offset+0x7a]
    code+=bytes([ops.GetEnvironment.opcode,4,0,ops.CreateClosure.opcode,4,4])+struct.pack('<H',23580)
    code+=bytes([ops.Call2.opcode,4,4,0,1])
    code+=original[h.offset+0x7a:h.offset+h.bytecodeSizeInBytes]
    replace(7933,bytes(code),'retain official translations and append new brand/type names')

    # NAS is a separate display type, with the same desktop-client eligibility
    # as a PC. Keep Apple's original remote-desktop exclusion intact.
    for fid,exclude_apple in ((32385,False),(32386,False),(32624,False),(32609,True),(32623,True)):
        c=bytearray([ops.LoadParam.opcode,1,1,ops.GetByIdShort.opcode,2,1,1,208,
            ops.LoadConstString.opcode,0])+struct.pack('<H',33676)
        c+=bytes([ops.StrictEq.opcode,0,2,0,ops.JmpTrue.opcode,0,0]);yes=len(c)-3
        c+=bytes([ops.LoadConstString.opcode,0])+struct.pack('<H',17308)
        c+=bytes([ops.StrictEq.opcode,0,2,0]);matched=len(c)
        if exclude_apple:
            c+=bytes([ops.JmpFalse.opcode,0,0]);no=len(c)-3
            c+=bytes([ops.GetById.opcode,2,1,1])+struct.pack('<H',25032)
            c+=bytes([ops.LoadConstString.opcode,1])+struct.pack('<H',24842)
            c+=bytes([ops.StrictNeq.opcode,0,2,1]);c[no+1]=len(c)-no
        c[yes+1]=matched-yes
        c+=bytes([ops.Ret.opcode,0])
        replace(fid,bytes(c),'retain NAS in PC-compatible desktop selection')
    h=reader.function_headers[32613]
    code=bytearray(original[h.offset:h.offset+h.bytecodeSizeInBytes])
    c=bytearray()
    for sid in (33676,17308):
        c+=bytes([ops.LoadConstString.opcode,1])+struct.pack('<H',sid)
        address=0x17+len(c)
        c+=bytes([ops.JStrictEqual.opcode,0x6e-address,4,1])
    address=0x17+len(c);c+=bytes([ops.Jmp.opcode,0x3b-address])
    assert len(c)<=0x3b-0x17
    c+=bytes([ops.Unreachable.opcode]*(0x3b-0x17-len(c)))
    code[0x17:0x3b]=c
    edit_generator(32613,bytes(code),'NAS follows the existing desktop configuration check')


def save_generator(reader):
    c=bytearray([ops.StartGenerator.opcode,ops.ResumeGenerator.opcode,0,1,
                 ops.JmpTrueLong.opcode,0,0,0,0,1,ops.GetEnvironment.opcode,2,1,
                 ops.LoadFromEnvironment.opcode,3,2,0,
                 ops.GetByIdShort.opcode,4,3,1,210,
                 ops.GetByIdShort.opcode,5,3,1,171,ops.NewObject.opcode,0,
                 ops.LoadFromEnvironment.opcode,6,2,4,
                 ops.PutNewOwnByIdShort.opcode,0,6,208,
                 ops.LoadFromEnvironment.opcode,6,2,6,ops.PutNewOwnById.opcode,0,6])+struct.pack('<H',25032)
    c+=bytes([ops.GetGlobalObject.opcode,6,ops.GetByIdShort.opcode,7,6,1,21,
              ops.GetByIdShort.opcode,6,7,1,232,ops.Call2.opcode,0,6,7,0,
              ops.GetEnvironment.opcode,6,2,ops.LoadFromEnvironment.opcode,7,6,0,
              ops.LoadConstUInt8.opcode,6,18,ops.LoadConstUndefined.opcode,3,
              ops.Call2.opcode,7,7,3,6,ops.GetById.opcode,7,7,1])+struct.pack('<H',22921)
    c+=bytes([ops.GetById.opcode,7,7,1])+struct.pack('<H',22266)
    c+=bytes([ops.GetById.opcode,6,7,1])+struct.pack('<H',33078)
    c+=bytes([ops.Call4.opcode,0,6,7,4,5,0,ops.SaveGenerator.opcode,4,ops.Ret.opcode,0,
              ops.ResumeGenerator.opcode,0,1,ops.JmpTrue.opcode,0,1])
    second=len(c)-3
    failed=len(c);c+=bytes([ops.JmpFalse.opcode,0,0,
        ops.LoadFromEnvironment.opcode,4,2,2,ops.Call1.opcode,4,4,3,
        ops.LoadFromEnvironment.opcode,4,2,1,ops.JmpFalse.opcode,0,4,ops.Call1.opcode,4,4,3])
    closed=len(c)-7
    done=len(c);c+=bytes([ops.CompleteGenerator.opcode,ops.Ret.opcode,0])
    struct.pack_into('<i',c,5,done-4)
    for jump in (second,failed,closed):c[jump+1]=done-jump
    assert len(c)<=reader.function_headers[33460].bytecodeSizeInBytes
    return bytes(c)

