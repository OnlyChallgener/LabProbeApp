"""A local assistant transport with explicit closure ownership and teardown.

Reuses only exclusive retired feedback storage. Original onmessage and
reconnect callbacks retain their lexical chains and bytecode.
"""
import ctypes
import json
from pathlib import Path

from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hbc_grow import insert_bodies
from patch_terminal_ipv6 import Code

# Retired feedback-URL storage, relabelled by patch_bundle.
BANNER_CACHE_SID = 7029


def additions(reader, original, replace, data):
    index = json.loads((Path(__file__).resolve().parents[2] /
                        'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    for fid in (23461, 23456, 23465, 23459):
        assert [f['id'] for f in index if fid in f['f']] == [23454]
        assert [i for i,h in enumerate(reader.function_headers)
                if h.offset == reader.function_headers[fid].offset] == [fid]
    assert [f['id'] for f in index if 23460 in f['f']] == [23454]
    assert [i for i,h in enumerate(reader.function_headers)
            if h.offset == reader.function_headers[23460].offset] == [23460]

    factory = Code(reader)
    factory.emit('CreateEnvironment', 0)
    factory.emit('NewObject', 2)
    factory.emit('StoreToEnvironment', 0, 0, 2)
    factory.emit('LoadConstFalse', 1)
    factory.emit('StoreToEnvironment', 0, 1, 1)
    for name, fid in (('onopen',35711), ('onmessage',35712), ('onerror',35716),
                      ('send',35717), ('close',23465)):
        factory.emit('CreateClosure', 1, 0, fid)
        factory.put(2, 1, name)
    factory.emit('GetGlobalObject', 3)
    factory.get(4, 3, 'setTimeout')
    factory.emit('CreateClosure',5,0,23459)
    factory.number(6, 20)
    # The environment stays in r0; Call3 must not overwrite it with a timer ID.
    factory.emit('Call3', 7, 4, 3, 5, 6)
    factory.get(4,3,'setInterval')
    factory.number(6,1000)
    factory.emit('Call3',7,4,3,5,6)
    factory.emit('StoreToEnvironment',0,2,7)
    factory.emit('Ret', 2)

    send = Code(reader)
    send.emit('GetEnvironment', 0, 0)
    send.emit('LoadFromEnvironment', 1, 0, 1)
    closed = send.branch('JmpTrue', 1)
    # WS -> useWebSocket -> Helper -> module (React Native in module slot 6).
    send.emit('GetEnvironment', 2, 3)
    send.emit('LoadFromEnvironment', 2, 2, 6)
    send.get(2, 2, 'NativeModules')
    send.get(2, 2, 'sta')
    send.emit('LoadConstString', 3, 7156)  # private assistantSend string
    send.emit('GetByVal', 3, 2, 3)
    send.emit('LoadParam', 4, 1)
    send.emit('StoreToEnvironment',0,3,4)
    send.emit('CreateClosure', 5, 0, 23461)
    send.emit('Call3', 6, 3, 2, 4, 5)
    send.finish(closed)
    send.emit('LoadConstUndefined', 0)
    send.emit('Ret', 0)

    callback = Code(reader)
    callback.emit('LoadParam', 1, 1)
    empty = callback.branch('JmpFalse', 1)
    callback.emit('GetEnvironment', 0, 0)
    callback.emit('LoadFromEnvironment', 2, 0, 1)
    closed = callback.branch('JmpTrue', 2)
    callback.emit('GetGlobalObject', 2)
    callback.get(3, 2, 'JSON')
    callback.get(4, 3, 'parse')
    callback.emit('Call2', 5, 4, 3, 1)
    callback.get(6, 5, 'forEach')
    callback.emit('LoadFromEnvironment', 7, 0, 0)
    callback.get(8, 7, 'onmessage')
    callback.emit('Call2', 9, 6, 5, 8)
    callback.finish(empty)
    callback.finish(closed)
    callback.emit('LoadConstUndefined', 0)
    callback.emit('Ret', 0)

    close = Code(reader)
    close.emit('GetEnvironment', 0, 0)
    close.emit('LoadConstTrue', 1)
    close.emit('StoreToEnvironment', 0, 1, 1)
    close.emit('GetGlobalObject',2)
    close.get(3,2,'clearInterval')
    close.emit('LoadFromEnvironment',4,0,2)
    close.emit('Call2',5,3,2,4)
    close.emit('LoadConstUndefined', 0)
    close.emit('Ret', 0)

    poll=Code(reader)
    poll.emit('GetEnvironment',0,0)
    poll.emit('LoadFromEnvironment',1,0,1)
    closed=poll.branch('JmpTrue',1)
    poll.emit('LoadFromEnvironment',1,0,4)
    opened=poll.branch('JmpTrue',1)
    poll.emit('LoadConstTrue',1)
    poll.emit('StoreToEnvironment',0,4,1)
    poll.emit('LoadFromEnvironment',2,0,0)
    poll.get(3,2,'onopen')
    poll.emit('Call1',4,3,2)
    poll.finish(opened)
    poll.emit('LoadFromEnvironment',1,0,3)
    empty=poll.branch('JmpFalse',1)
    poll.emit('GetGlobalObject',2)
    poll.get(2,2,'JSON')
    poll.get(3,2,'parse')
    poll.emit('Call2',3,3,2,1)
    poll.string(4,'poll') if 'poll' in reader.strings else poll.emit('LoadConstString',4,7164)
    for field in ('requestMessageType','type'):
        poll.string(5,field)
        poll.emit('PutByVal',3,5,4)
    poll.string(4,'')
    poll.string(5,'messageDetail')
    poll.emit('PutByVal',3,5,4)
    poll.get(4,2,'stringify')
    poll.emit('Call2',5,4,2,3)
    poll.emit('LoadFromEnvironment',2,0,0)
    poll.get(3,2,'send')
    poll.emit('Call2',4,3,2,5)
    poll.finish(empty)
    poll.finish(closed)
    poll.emit('LoadConstUndefined',0)
    poll.emit('Ret',0)

    # Until the audited message feed is connected, render one honest static
    # entry with the original card styles. Both card and avatar navigate to
    # Helper; no empty cloud notification becomes a blank untappable row.
    # ToolXiaoxing runs useEffect(..., [items]) and stores items.filter(...),
    # so a freshly built array on every render re-arms the effect forever.
    # Cache the array on the global object to keep its identity stable.
    banner = Code(reader)
    banner.emit('GetEnvironment',8,0)
    banner.emit('GetGlobalObject',0)
    banner.emit('LoadConstString',1,BANNER_CACHE_SID)
    banner.emit('GetByVal',1,0,1)
    cached = banner.branch('JmpTrue',1)
    banner.emit('NewObject',2)
    banner.emit('CreateClosure',1,8,24149)
    banner.put(2,1,'onClick')
    banner.emit('LoadConstString',1,20357)
    banner.put(2,1,'content')
    banner.emit('NewArray',3,1)
    banner.emit('PutOwnByIndex',3,2,0)
    banner.emit('LoadConstString',1,BANNER_CACHE_SID)
    banner.emit('PutByVal',0,1,3)
    banner.emit('Ret',3)
    banner.finish(cached)
    banner.emit('Ret',1)
    old=reader.function_headers[24141]
    body=bytearray(original[old.offset:old.offset+old.bytecodeSizeInBytes])
    init=Code(reader)
    init.emit('GetEnvironment',0,0)
    init.emit('CreateClosure',8,15,23460)
    init.emit('Call1',8,8,3)
    init.emit('StoreToEnvironment',15,0,8)
    assert len(init.raw)==0x14-0x4
    body[0x4:0x14]=init.raw
    replace(24141,bytes(body),'supply assistant card without retired cloud banner data')

    replace(35710, bytes(factory.raw), 'assistant transport with owned environment')
    replace(23461, bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]), 'reserve assistant result callback header')
    replace(23465, bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]), 'reserve assistant close header')
    replace(23459, bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]), 'reserve scoped assistant poll header')
    # Use retired storage because send is larger than the original onclose.
    replace(23456, bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]),
            'reserve exclusive feedback storage for assistant send')
    replace(35717, bytes([ops.LoadConstUndefined.opcode,0,ops.Ret.opcode,0]),
            'rehome assistant send without overwriting live callbacks')
    storage = reader.function_headers[23456]
    assert len(send.raw)+len(callback.raw) <= storage.bytecodeSizeInBytes - 4
    offset = storage.offset + 4
    data[offset:offset + len(send.raw)] = send.raw
    callback_offset=offset+len(send.raw)
    data[callback_offset:callback_offset+len(callback.raw)]=callback.raw
    data, placed = insert_bodies(reader, data, [close.raw, poll.raw, banner.raw])
    (close_offset, _), (poll_offset, _), (banner_offset, _) = placed

    small = reader.get_small_func_header_reader()
    def meta(fid):
        raw = small.from_buffer(data, reader.functions_offset + fid*ctypes.sizeof(small))
        return (reader.get_large_func_header_reader().from_buffer(data,
                    (raw.infoOffset << 16) | raw.offset) if raw.overflowed else raw)
    for fid in (35710,35717,23461,23465,23460,23459):
        h = meta(fid)
        h.frameSize = 28
        h.paramCount = 2 if fid in (35717,23461) else 1
        h.environmentSize = 5 if fid == 35710 else 0
        h.highestReadCacheIndex = 16
        h.highestWriteCacheIndex = 8
        h.hasExceptionHandler = 0
    h = meta(35717)
    h.offset, h.bytecodeSizeInBytes = offset, len(send.raw)
    h=meta(23461)
    h.offset,h.bytecodeSizeInBytes=callback_offset,len(callback.raw)
    h=meta(23465);h.offset,h.bytecodeSizeInBytes=close_offset,len(close.raw)
    h=meta(23459);h.offset,h.bytecodeSizeInBytes=poll_offset,len(poll.raw)
    h=meta(23460);h.offset,h.bytecodeSizeInBytes=banner_offset,len(banner.raw)
    meta(23456).bytecodeSizeInBytes = 4
    return data
