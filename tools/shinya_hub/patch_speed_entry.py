"""Restore native router speed testing from both original speed entry points.

Read the existing Redux store only when clicked, as original getProjectId
F20133 does. Do not add hooks or change Speed's environment/register frame.
"""
import ctypes
from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_assistant_notifications import C
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
STRINGS={}
SELECTOR,OPEN=20080,20081

def selector(r):
    c=C(r);c.emit('LoadParam',0,1);c.get(0,0,'network');c.get(0,0,'curProject');c.emit('Ret',0);return c

def launch(r,tool):
    c=C(r);c.emit('LoadConstUndefined',1);c.emit('GetEnvironment',0,1)
    c.emit('LoadFromEnvironment',2,0,12 if tool else 5);c.get(2,2,'NativeModules');c.get(2,2,'sta');c.string(3,'speedTest');c.emit('GetByVal',3,2,3)
    # module1934 is the original store factory F20570. F20133 uses its
    # store.getState().network.curProject.projectId for original API calls.
    c.emit('LoadFromEnvironment',4,0,0);c.number(5,1934);c.emit('Call2',4,4,1,5)
    c.get(4,4,'store');c.get(5,4,'getState');c.emit('Call1',4,5,4)
    c.get(4,4,'network');c.get(4,4,'curProject')
    no_project=c.branch('JmpFalseLong',4);c.get(4,4,'projectId');missing=c.branch('JmpFalseLong',4)
    c.string(5,'');c.emit('Call3',3,3,2,4,5);c.finish(missing);c.finish(no_project);c.emit('Ret',1);return c

def additions(reader,data,changed,changes):
    from pathlib import Path
    import json
    from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
    ix=json.loads((Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'));by={v['id']:v for v in ix}
    retired=set();todo=[20053]
    while todo:
        f=todo.pop()
        if f not in retired:retired.add(f);todo.extend(by[f]['f'])
    for f in (OPEN,):
        assert f in retired and all(v['id'] in retired for v in ix if f in v['f'])
        if f in changed:
            h=reader.function_headers[f];raw=bytes(data[h.offset:h.offset+h.bytecodeSizeInBytes]);noop=bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0])
            assert raw==noop+bytes([ops.Unreachable.opcode]*(len(raw)-len(noop))),('speed helper already claimed',f)
    bodies={OPEN:launch(reader,False),23925:launch(reader,True)}
    h=C(reader,reader.function_headers[33131].highestReadCacheIndex)
    h.emit('GetEnvironment',20,1);h.emit('LoadFromEnvironment',20,20,5)
    h.get(21,20,'Text');h.emit('NewObject',22);h.string(23,'测网速');h.put(22,23,'children')
    h.emit('NewObject',24);h.number(25,14);h.put(24,25,'fontSize');h.number(25,20);h.put(24,25,'lineHeight')
    h.string(25,'#26bcbf');h.put(24,25,'color');h.number(25,10);h.put(24,25,'paddingHorizontal');h.number(25,8);h.put(24,25,'paddingVertical');h.put(22,24,'style')
    h.emit('Call3',21,2,3,21,22);h.get(20,20,'TouchableNativeFeedback')
    h.emit('NewObject',22);h.put(22,21,'children');h.emit('GetEnvironment',23,0);h.emit('CreateClosure',23,23,OPEN);h.put(22,23,'onPress')
    h.emit('Call3',20,2,3,20,22)
    h.emit('NewArray',21,2);h.emit('PutOwnByIndex',21,20,0);h.emit('PutOwnByIndex',21,4,1)
    h.emit('NewObject',22);h.put(22,21,'children');h.emit('NewObject',24);h.string(25,'row');h.put(24,25,'flexDirection');h.string(25,'center');h.put(24,25,'alignItems');h.put(22,24,'style')
    h.emit('Call3',4,2,3,1,22)
    header,_,_=rewrite(reader,33131,{0x118:bytes(h.raw)},{})
    raw={f:bytes(v.raw) for f,v in bodies.items()};raw.update({33131:header})
    out,placed=insert_bodies(reader,data,list(raw.values()));small=reader.get_small_func_header_reader()
    for f,loc in zip(raw,placed):
        m=small.from_buffer(out,reader.functions_offset+f*ctypes.sizeof(small));assert not m.overflowed
        m.offset,m.bytecodeSizeInBytes=loc
        if f in (OPEN,23925):m.frameSize=max(m.frameSize,16)
        if f==SELECTOR:m.frameSize=max(m.frameSize,1)
        if f==33131:m.frameSize=max(m.frameSize,36)
        if f in bodies:m.highestReadCacheIndex=bodies[f].cache;m.highestWriteCacheIndex=len(bodies[f].writes);m.hasExceptionHandler=0
        if f in (SELECTOR,OPEN):m.paramCount=2;m.environmentSize=0
        if f==33131:m.highestReadCacheIndex=h.cache
        changed.add(f);del m
    changes.append({'function':23925,'action':'speed entries read original store at click time; Speed page body and hook order stay original'})
    return out
