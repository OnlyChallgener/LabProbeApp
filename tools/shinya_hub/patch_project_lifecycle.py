"""Read the new home's ID from the HUB's structured creation response."""
import ctypes
from hbc_grow import insert_bodies
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops


def additions(reader, data, changed, changes):
    fid = 26437
    header = reader.function_headers[fid]
    body = bytes(data[header.offset:header.offset + header.bytecodeSizeInBytes])
    assert body[0x5c:0x61] == bytes([ops.GetByIdShort.opcode, 6, 5, 5, 110])
    # This closure has no branches or exception ranges. Keep the structured
    # response for native onboarding; only the Redux project selector needs ID.
    # Dispatch the selected new home before refreshing the list.
    original_body = body
    body = body[:0x61] + bytes([ops.GetByIdShort.opcode, 6, 6, 7, 210]) + body[0x61:]
    # The old 600ms timer let MainPage mount with the previous default home.
    # Keep the original generator's size, branches and exception table intact.
    generator = reader.function_headers[26436]
    original_generator=bytes(data[generator.offset:generator.offset+generator.bytecodeSizeInBytes])
    assert original_generator[0xfa] == ops.TryGetById.opcode
    replacement=bytes([ops.CreateClosure.opcode,15,3])+int(fid).to_bytes(2,'little')+bytes([ops.Call1.opcode,13,15,1])
    while len(replacement)<0x111-0xfa:replacement+=bytes([ops.LoadConstUndefined.opcode,13])
    assert len(replacement)==0x111-0xfa
    data[generator.offset+0xfa:generator.offset+0x111]=replacement
    changed.add(26436)
    changes.append({'function':26436,'action':'select created home synchronously before MainPage reset'})
    # Refresh now captures the newly selected state instead of the old default.
    body=body[:2]+bytes([ops.GetEnvironment.opcode,1,3])+body[0x2e:-2]+body[2:0x2e]+body[-2:]
    result, placed = insert_bodies(reader, data, [body])
    small = reader.get_small_func_header_reader()
    h = small.from_buffer(result, reader.functions_offset + fid * ctypes.sizeof(small))
    assert not h.overflowed
    h.offset, h.bytecodeSizeInBytes = placed[0]
    h.highestReadCacheIndex=max(h.highestReadCacheIndex,7)
    del h
    changed.add(fid)
    changes.append({'function': fid, 'action': 'select created home by response.data.projectId'})
    return result
