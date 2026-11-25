"""Make the existing game-environment history action strictly read-only.

Keep the original request, await, empty-result UI and catch table in place.
The loaded snapshot is rendered directly; never resume check/optimize timers.
"""
import ctypes
from hbc_grow import insert_bodies
from patch_assistant_history import C
from patch_measured_status import rewrite

def additions(reader,data,changed,changes):
    # The existing timer ref survives hook renders. Track the current router
    # there so a late history request cannot paint another router's page.
    c=C(reader,reader.function_headers[35513].highestReadCacheIndex)
    c.emit('LoadFromEnvironment',40,0,8)
    c.emit('LoadFromEnvironment',41,0,12)
    c.emit('LoadConstUndefined',42);missing=c.branch('JmpFalseLong',41)
    c.get(42,41,'sn');c.finish(missing);c.set(40,42,'sn')
    body,_,_=rewrite(reader,35513,{0x3b7:bytes(c.raw)},{})
    result,placed=insert_bodies(reader,data,[body]);small=reader.get_small_func_header_reader()
    h=small.from_buffer(result,reader.functions_offset+35513*ctypes.sizeof(small))
    assert not h.overflowed
    h.offset,h.bytecodeSizeInBytes=placed[0];h.frameSize=max(h.frameSize,64)
    h.highestReadCacheIndex=c.cache;h.highestWriteCacheIndex=max(h.highestWriteCacheIndex,1)
    del h
    c=C(reader,reader.function_headers[35523].highestReadCacheIndex)
    c.emit('LoadFromEnvironment',40,5,8);c.get(41,40,'sn')
    stale=c.branch('JStrictNotEqualLong',41,11)
    c.emit('LoadFromEnvironment',40,5,9);c.emit('Call2',40,40,1,6)
    c.emit('LoadFromEnvironment',40,5,7);c.get(41,6,'id');c.emit('Call2',40,40,1,41)
    c.emit('LoadFromEnvironment',40,5,10);c.emit('LoadFromEnvironment',41,2,10)
    c.get(42,6,'allOptimizeStatus');check=c.branch('JmpFalseLong',42)
    c.get(41,41,'optimize');tag_ready=c.branch('JmpLong')
    c.finish(check);c.get(41,41,'check');c.finish(tag_ready)
    c.emit('Call2',40,40,1,41)
    c.emit('LoadFromEnvironment',40,5,3);c.emit('LoadFromEnvironment',41,2,8)
    c.get(41,41,'showResult');c.emit('Call2',40,40,1,41)
    c.finish(stale)
    c.emit('LoadFromEnvironment',40,2,4);c.get(40,40,'default');c.get(41,40,'remove')
    c.emit('Call2',41,41,40,3);c.emit('CompleteGenerator');c.emit('Ret',1)
    # Retain offsets, generator entry/resume and original exception handlers.
    start,end=0xbf,0x40e
    assert len(c.raw)<=end-start
    h=reader.function_headers[35523]
    from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
    result[h.offset+start:h.offset+end]=bytes(c.raw)+bytes([ops.Unreachable.opcode])*(end-start-len(c.raw))
    meta=small.from_buffer(result,reader.functions_offset+35523*ctypes.sizeof(small))
    assert not meta.overflowed
    meta.frameSize=max(meta.frameSize,64);meta.highestReadCacheIndex=c.cache
    del meta
    changed.update((35513,35523))
    changes.append({'function':35523,'action':'history snapshot only; never resume game check or optimization; reject late other-router results'})
    return result
