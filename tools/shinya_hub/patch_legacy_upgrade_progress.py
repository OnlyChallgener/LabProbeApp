"""Keep the original upgrade screen and feed it measured HUB progress.

F24065 polls getSysinfo and previously advanced its displayed progress by three
on every poll.  HUB adds a scoped, fresh `progress` field while a legacy
upgrade is active. Preserve the existing value when the measurement is absent.
The byte lengths and generator control-flow offsets remain unchanged.
"""
import struct
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops


def apply(reader, original, replace):
    header = reader.function_headers[24065]
    body = bytearray(original[header.offset:header.offset + header.bytecodeSizeInBytes])
    # Keep the original bounded pre-flash wait; confirmed download followed by
    # disconnection exits this poll without reporting a failure or a success.
    assert body[0x69:0x6c] == bytes([ops.LoadConstUInt8.opcode,3,30])
    # Save the real sysinfo result in r20.  The displaced move merely copied
    # upgradeInfo into r8; the following property read can use r10 directly.
    assert body[0xcc:0xd5] == bytes.fromhex('08 08 0a 35 08 08 08 79 ab')
    body[0xcc:0xcf] = bytes([ops.Mov.opcode,20,1])
    body[0xd1] = 10
    start, end = 0x19c, 0x1c1
    assert body[start:end] == bytes.fromhex(
        '37 1a 05 0f 16 00 34 16 1a 10 b8 35 08 01 09 03 bf '
        '47 08 08 07 35 08 08 11 5e 6b 16 08 08 10 52 08 16 1a 08 0f')
    code = bytearray([ops.GetById.opcode,8,20,17]) + struct.pack('<H',27486)
    branch = len(code)
    code += bytes([ops.JNotEqual.opcode,0,8,4])  # non-null measured value
    code += bytes([ops.GetById.opcode,8,1,9]) + struct.pack('<H',48899)
    code += bytes([ops.GetByVal.opcode,8,8,7])
    code += bytes([ops.GetById.opcode,8,8,17]) + struct.pack('<H',27486)
    check = len(code)
    code += bytes([ops.LoadConstZero.opcode,26])
    failure = len(code)
    code += bytes([ops.JLessLong.opcode]) + struct.pack('<i',0) + bytes([8,26])
    jump = len(code)
    code += bytes([ops.Jmp.opcode,0])
    code[branch + 1] = check - branch
    code[failure+1:failure+5] = struct.pack('<i',0x228-(start+failure))
    code[jump + 1] = end - (start + jump)
    assert len(code) <= end - start
    body[start:end] = code + bytes([ops.Unreachable.opcode]) * (end - start - len(code))
    # A measured -2 means download completed and the device subsequently went
    # offline. Return truthy to suppress the caller's failure toast, but do not
    # change progressInfo to success: the actual firmware version is unverified.
    dispatch_start, dispatch_end = 0x228, 0x266
    dispatch = bytearray([ops.LoadConstInt.opcode,6]) + struct.pack('<i',-2)
    branch_pending = len(dispatch)
    dispatch += bytes([ops.JStrictEqual.opcode,0,8,6])
    branch_failed = len(dispatch)
    dispatch += bytes([ops.Jmp.opcode,0])
    pending = len(dispatch)
    dispatch += bytes([ops.LoadConstTrue.opcode,13,ops.CompleteGenerator.opcode,ops.Ret.opcode,13])
    dispatch[branch_pending+1] = pending-branch_pending
    dispatch[branch_failed+1] = dispatch_end-(dispatch_start+branch_failed)
    body[dispatch_start:dispatch_end] = dispatch + bytes([ops.Unreachable.opcode])*(dispatch_end-dispatch_start-len(dispatch))
    replace(24065, bytes(body), 'use measured firmware download progress on original upgrade page')
    header = reader.function_headers[24073]
    caller = bytearray(original[header.offset:header.offset + header.bytecodeSizeInBytes])
    assert caller[0xcc:0xd2] == bytes([ops.LoadConstInt.opcode,2])+struct.pack('<i',60000)
    caller[0xce:0xd2] = struct.pack('<i',1000)
    replace(24073,bytes(caller),'start real upgrade polling immediately after command acknowledgement')
