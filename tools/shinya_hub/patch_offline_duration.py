"""Keep missing offline timestamps out of the original epoch-date calculation."""
import struct
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

def additions(reader,original,replace,edit_generator):
    # The station-detail banner uses formatInactiveTime, which the original
    # formatter turns into the literal "Invalid Date" for an absent timestamp.
    # Keep its usual formatting for real dates. When the firmware has no
    # timestamp, the localized suffix is "下线"; "已" produces "已下线"
    # without suggesting a fabricated date. Reuse the formatter/module
    # registers left intact by the preceding active-time call.
    fid=22164;h=reader.function_headers[fid];start,end=55,81
    before=original[h.offset:h.offset+h.bytecodeSizeInBytes]
    insts={i.original_pos:i for i in parse_hbc_bytecode(h,reader)}
    assert insts[50].inst.name=='Call2' and insts[76].inst.name=='Call2'
    assert insts[14].inst.name=='GetById' and insts[14].arg4==reader.strings.index('inactivetime')
    shown=reader.strings.index('已')
    code=bytearray([ops.JmpFalse.opcode,10,5,
                    ops.Call2.opcode,2,1,2,5,
                    ops.Jmp.opcode,6,
                    ops.LoadConstString.opcode,2])+struct.pack('<H',shown)
    code+=bytes([ops.LoadConstUndefined.opcode,5])*6
    assert len(code)==end-start
    replace(fid,before[:start]+bytes(code)+before[end:],
            'guard the station-detail offline date before formatting')

    fid=33187;h=reader.function_headers[fid];start,end=1625,1745
    before=original[h.offset:h.offset+h.bytecodeSizeInBytes]
    insts={i.original_pos:i for i in parse_hbc_bytecode(h,reader)}
    assert insts[1690].inst.name=='GetById' and insts[1690].arg4==reader.strings.index('latestDevTime')
    assert insts[1740].inst.name=='Call2'
    b=bytearray([ops.GetById.opcode,33,0,31])+struct.pack('<H',reader.strings.index('latestDevTime'))
    guard=len(b);b+=bytes([ops.JmpFalse.opcode,0,33])
    b+=before[1625:1646]
    b+=bytes([ops.GetGlobalObject.opcode,10,ops.TryGetById.opcode,25,10,32])+struct.pack('<H',reader.strings.index('Date'))
    b+=bytes([ops.GetByIdShort.opcode,23,25,35,reader.strings.index('now'),ops.Call1.opcode,23,23,25])
    b+=before[1696:1745]
    jump=len(b);b+=bytes([ops.Jmp.opcode,0])
    missing=len(b);b+=bytes([ops.LoadConstString.opcode,20])+struct.pack('<H',reader.strings.index('暂无记录'))
    finish=len(b);assert missing-guard<128 and finish-jump<128
    b[guard+1]=missing-guard;b[jump+1]=finish-jump
    remaining=end-start-len(b);assert remaining>=2
    if remaining%2:b+=bytes([ops.Mov.opcode,10,10]);remaining-=3
    b+=bytes([ops.LoadConstUndefined.opcode,10])*(remaining//2)
    assert len(b)==end-start
    replace(fid,before[:start]+bytes(b)+before[end:],'show missing offline time without calculating from Unix epoch')

    # The official history screen inserts a Date.now() disconnect when a
    # terminal is currently offline but the latest recorded event is online.
    # That turns a missing event into the misleading "刚刚下线" row. Leave the
    # confirmed history intact and skip only this fabricated current-time row.
    fid=33346;h=reader.function_headers[fid];before=original[h.offset:h.offset+h.bytecodeSizeInBytes]
    insts={i.original_pos:i for i in parse_hbc_bytecode(h,reader)}
    assert insts[0x486].inst.name=='LoadFromEnvironment'
    assert insts[0x48a].inst.name=='GetByIdShort'
    assert insts[0x4a8].inst.name=='Call2'
    assert insts[0x50a].inst.name=='LoadFromEnvironment'
    skip=bytes([ops.JmpLong.opcode])+struct.pack('<i',0x50a-0x486)
    skip+=bytes([ops.LoadConstUndefined.opcode,9])*2
    assert len(skip)==0x48f-0x486
    edit_generator(fid,before[:0x486]+skip+before[0x48f:],
                   'omit the fabricated just-now disconnect from station history')
