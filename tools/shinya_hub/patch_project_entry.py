"""Route home add buttons to the authenticated HUB/MACC setup form."""
import ctypes
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hbc_grow import insert_bodies
from patch_terminal_ipv6 import Code


def additions(reader, replace, data):
    c=Code(reader)
    c.emit('GetGlobalObject',0)
    c.get(1,0,'__r')
    c.emit('LoadConstUndefined',3)
    c.emit('LoadConstUInt8',2,18)
    c.emit('Call2',0,1,3,2)
    c.get(0,0,'NativeModules')
    c.get(0,0,'sta')
    c.emit('LoadConstString',1,22216)
    c.emit('GetByVal',1,0,1)
    c.string(2,'')
    c.string(4,'add')
    c.emit('Call3',2,1,0,2,4)
    c.emit('LoadConstUndefined',0)
    c.emit('Ret',0)
    small=reader.get_small_func_header_reader()
    def meta(fid):
        h=small.from_buffer(data,reader.functions_offset+fid*ctypes.sizeof(small))
        return (reader.get_large_func_header_reader().from_buffer(data,
                    (h.infoOffset<<16)|h.offset) if h.overflowed else h)
    replace(21901,bytes(c.raw),'home add opens verified HUB/MACC setup')
    replace(22907,bytes([ops.LoadConstUndefined.opcode,0,ops.Ret.opcode,0]),
            'empty-home add opens the same verified setup')
    data,[(offset,_)]=insert_bodies(reader,data,[c.raw])
    for fid in (21901,22907):
        h=meta(fid)
        if fid==22907:h.offset,h.bytecodeSizeInBytes=offset,len(c.raw)
        h.frameSize=24;h.paramCount=1;h.environmentSize=0
        h.highestReadCacheIndex=8;h.highestWriteCacheIndex=0;h.hasExceptionHandler=0
    # The assistant action now also reads the captured project ID.
    meta(35703).frameSize=16
    return data
