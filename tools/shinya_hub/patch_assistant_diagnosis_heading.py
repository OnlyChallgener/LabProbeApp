"""Keep the original diagnosis card, but do not call missing data poor health.

Original DiagnoseCard renders its orange failure headline for both failures and
incomplete observations. Only a positive, measured exception count supports
the original 'current network is poor' headline. Zero/missing counts use the
existing unavailable label; no category, route or health score is invented.
"""
import ctypes

from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

FID = 35934
SITE = 0x8f2
EXCEPTIONS = 37543


def snippet(reader, *, placeholder=12897):
    header = reader.function_headers[FID]
    temporary = header.frameSize
    c = Code(reader)
    c.emit('LoadFromEnvironment', temporary, 12, 1)
    c.emit('GetById', temporary + 1, temporary, header.highestReadCacheIndex + 1, EXCEPTIONS)
    c.emit('LoadConstZero', temporary + 2)
    c.emit('LoadConstString', 27, placeholder)
    unknown = c.branch('JNotGreater', temporary + 1, temporary + 2)
    c.emit('LoadConstString', 27, 19592)
    c.finish(unknown)
    return bytes(c.raw)


def additions(reader, data, changed, changes, *, placeholder=12897):
    assert reader.strings[19592] == '当前网络不佳'
    assert reader.strings[EXCEPTIONS] == 'exceptionNumber'
    old = reader.function_headers[FID]
    completed = Code(reader)
    # The caller applies the completion label to this existing literal slot
    # after all function patches have run.
    completed.emit('LoadConstString', 29, placeholder)
    body, _, _ = rewrite(reader, FID, {}, {
        # A completed observation may contain abnormal or unmeasured items.
        # Completion never means the entire network is healthy.
        0x850: ('LoadConstString', [29, 19863], bytes(completed.raw)),
        SITE: ('LoadConstString', [27, 19592], snippet(reader, placeholder=placeholder))})
    result, locations = insert_bodies(reader, data, [body])
    small = reader.get_small_func_header_reader()
    meta = small.from_buffer(result, reader.functions_offset + FID * ctypes.sizeof(small))
    if meta.overflowed:
        meta = reader.get_large_func_header_reader().from_buffer(result, (meta.infoOffset << 16) | meta.offset)
    meta.offset, meta.bytecodeSizeInBytes = locations[0]
    # New locals are beyond the old frame; reserve outgoing CallN space too.
    # This component has no implicit Call/Construct argument layout to move.
    meta.frameSize = max(old.frameSize + 3 + 10,60)
    meta.highestReadCacheIndex = old.highestReadCacheIndex + 1
    del meta
    changed.add(FID)
    changes.append({'function': FID, 'action': 'retain original poor-health headline only for measured positive exception count'})
    return result
