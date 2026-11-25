"""Update an existing assistant task row without changing its React key."""
import ctypes
import struct
from hbc_grow import insert_bodies
from patch_assistant_cards import C

def additions(reader,data,changed,changes):
    c=C(reader)
    c.emit('LoadParam',0,1)
    c.get(1,0,'slice');c.emit('LoadConstUndefined',2);c.emit('Call1',3,1,0)
    c.emit('GetEnvironment',4,0);c.emit('LoadFromEnvironment',4,4,1)
    c.get(5,4,'uuid');append=c.branch('JmpFalseLong',5)
    c.get(6,3,'length');c.emit('LoadConstZero',7)
    loop=len(c.raw);done=c.branch('JNotLessLong',7,6)
    c.emit('GetByVal',8,3,7);c.get(9,8,'uuid');other=c.branch('JStrictNotEqualLong',9,5)
    c.get(9,8,'sender');c.get(10,4,'sender');sender=c.branch('JStrictNotEqualLong',9,10)
    c.get(9,8,'key');c.put(4,9,'key')
    c.emit('PutByVal',3,7,4);c.emit('Ret',3)
    c.finish(other);c.finish(sender);c.emit('Inc',7,7);back=c.branch('JmpLong')
    struct.pack_into('<i',c.raw,back+1,loop-back)
    c.finish(done);c.finish(append)
    c.get(1,3,'push');c.emit('Call2',1,1,3,4);c.emit('Ret',3)
    # Diagnosis has its own original progress/component lifecycle. Only the
    # added V2 result cards need this stable-row reducer; leave diagnosis's
    # reducer byte-for-byte intact instead of changing its remount semantics.
    bodies={35734:c}
    # Keep the original loader, hydration generation and save effects. Replace
    # just their storage facade with an owner/project-scoped durable journal.
    for fid,method in ((8558,'hGet'),(8557,'hSet')):
        storage=C(reader);storage.emit('GetGlobalObject',0);storage.get(1,0,'__r')
        storage.number(2,18);storage.emit('LoadConstUndefined',3);storage.emit('Call2',0,1,3,2)
        storage.get(0,0,'NativeModules');storage.get(0,0,'sta')
        storage.emit('LoadConstString',1,18544 if method=='hGet' else 1330);storage.emit('GetByVal',1,0,1)
        storage.emit('LoadParam',2,1)
        if method=='hGet':storage.emit('Call2',3,1,0,2)
        else:
            storage.emit('LoadParam',4,2);storage.emit('Call3',3,1,0,2,4)
        storage.emit('Ret',3);bodies[fid]=storage
    result,placed=insert_bodies(reader,data,[bytes(body.raw) for body in bodies.values()])
    for fid,location in zip(bodies,placed):
        meta=reader.get_small_func_header_reader().from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(reader.get_small_func_header_reader()))
        assert not meta.overflowed
        meta.offset,meta.bytecodeSizeInBytes=location;meta.frameSize=20
        meta.highestReadCacheIndex=bodies[fid].cache;meta.highestWriteCacheIndex=0;meta.hasExceptionHandler=0
        changed.add(fid);del meta
    changes.append({'function':35734,'action':'upsert task messages by sender/uuid; retain row key and position'})
    return result
