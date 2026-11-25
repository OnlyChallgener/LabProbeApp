"""Retain the original child report identity query across page remounts."""
import ctypes
from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

def additions(reader,data,changed,changes):
    fid=36086;h=reader.function_headers[fid]
    assert h.frameSize>28 and not h.hasExceptionHandler
    insertions={}
    for pos,obj in [(624,5),(1128,4)]:
        c=Code(reader);c.number(28,300000)
        c.emit('PutById',obj,28,1,reader.strings.index('cacheTime'))
        insertions[pos]=bytes(c.raw)
    c=Code(reader);c.emit('Mov',7,5)
    body,_,_=rewrite(reader,fid,insertions,{200:('NewObject',[7],bytes(c.raw))})
    # Initial React Query data may be undefined while a background request runs.
    # Do not erase the route/cached device before a valid response arrives.
    c=Code(reader);skip=c.branch('JmpFalseLong',2);c.emit('Call2',2,3,0,2);c.finish(skip)
    effect,_,_=rewrite(reader,36095,{}, {13:('Call2',[2,3,0,2],bytes(c.raw))})
    result,placed=insert_bodies(reader,data,[body,effect]);small=reader.get_small_func_header_reader()
    for target,location in zip([fid,36095],placed):
        header=small.from_buffer(result,reader.functions_offset+target*ctypes.sizeof(small))
        assert not header.overflowed
        header.offset,header.bytecodeSizeInBytes=location;del header
        changed.add(target)
    changes.append({'function':fid,'action':'retain child identity/plan queries for five minutes; initialize from routed terminal; preserve content during refresh'})
    return result
