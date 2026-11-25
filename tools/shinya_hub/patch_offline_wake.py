"""Keep the original row UI, gating wake on the HUB's scoped WOL flag."""
import ctypes
from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

def additions(reader, data, changed, changes):
    fid = 22136
    h = reader.function_headers[fid]
    assert not h.hasExceptionHandler
    # The existing branch already checks the row is offline. It also checked
    # a router-wide remote-desktop capability and excluded only phones. Replace
    # those two tests with the server's per-station, fresh capability flag.
    c = Code(reader); c.string(24, '1')
    first = bytes(c.raw)
    c = Code(reader)
    c.emit('LoadFromEnvironment', 13, 6, 0)
    c.emit('GetById', 24, 13, h.highestReadCacheIndex + 1, c.sid('remotectrl'))
    c.emit('LoadConstFalse', 13)
    replacements = {
        0xa7b: ('AddEmptyString', [24, 13], first),
        0xa99: ('LoadConstString', [13, reader.strings.index('phone')], bytes(c.raw)),
    }
    body, _, _ = rewrite(reader, fid, {}, replacements)
    data, locations = insert_bodies(reader, data, [body])
    small = reader.get_small_func_header_reader()
    meta = small.from_buffer(data, reader.functions_offset + fid * ctypes.sizeof(small))
    if meta.overflowed:
        meta = reader.get_large_func_header_reader().from_buffer(data, (meta.infoOffset << 16) | meta.offset)
    meta.offset, meta.bytecodeSizeInBytes = locations[0]
    meta.highestReadCacheIndex = h.highestReadCacheIndex + 1
    changed.add(fid)
    changes.append({'function':fid, 'action':'show wake only for eligible offline station', 'appendedBytes':len(body)})
    del meta
    return data
