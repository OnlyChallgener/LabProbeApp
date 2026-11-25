"""Route original More to an assistant directory and add two original-style pills.

Run before assistant_history: the original exception-bearing shortcut function
keeps its exact length here, so history can subsequently rebase its own insert.
The two Chinese labels are owned by assistant_cards (SID31265/SID14103); no
additional private strings are consumed. All other strings already exist.
"""
import ctypes
import json
from pathlib import Path

from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

OPEN, SELECTED = 20062, 35704
STRINGS = {}
RANK_NAME, DAILY_NAME = 31265, 14103


class C(Code):
    def __init__(self, reader, cache=0):
        super().__init__(reader);self.cache=cache
    def get(self, dst, obj, name):
        self.cache+=1;self.emit('GetById',dst,obj,self.cache,self.sid(name))


def opener(reader):
    c=C(reader)
    c.emit('GetEnvironment',0,0)  # captured Helper instance, not a mutable global
    c.emit('LoadParam',1,1)
    c.get(2,1,'name')
    c.emit('LoadConstString',3,RANK_NAME);rank=c.branch('JStrictEqual',2,3)
    c.emit('LoadConstString',3,DAILY_NAME);daily=c.branch('JStrictEqual',2,3)
    c.emit('LoadFromEnvironment',4,0,19)
    c.get(5,4,'navigate');c.string(6,'Tool')
    c.emit('Call2',8,5,4,6)
    c.emit('LoadConstUndefined',8);c.emit('Ret',8)
    c.finish(rank);c.finish(daily)
    c.emit('LoadFromEnvironment',4,0,33)
    c.emit('LoadConstUndefined',3);c.string(5,'chat')
    c.emit('Call3',6,4,3,2,5);c.emit('Ret',3)
    return c


def selected(reader):
    c=C(reader)
    c.emit('LoadParam',1,1);c.emit('TypeOf',2,1);c.string(3,'string')
    invalid=c.branch('JStrictNotEqual',2,3);empty=c.branch('JmpFalse',1)
    c.emit('GetEnvironment',0,0);c.emit('LoadFromEnvironment',4,0,33)
    c.emit('LoadConstUndefined',3);c.string(5,'chat')
    c.emit('Call3',6,4,3,1,5)
    c.finish(invalid);c.finish(empty);c.emit('LoadConstUndefined',3);c.emit('Ret',3)
    return c


def footer(reader):
    c=C(reader);c.emit('NewArray',0,6)
    # Keep original diagnose/clear/share/upgrade data and their order. Feedback
    # was intentionally removed by the existing main patch and stays removed.
    for index,literal in enumerate((143206,143220,143227,143234)):
        c.emit('NewObjectWithBufferLong',1,3,3,53638,literal)
        c.emit('PutOwnByIndex',0,1,index)
    for index,name,key in ((4,RANK_NAME,'bandwidth'),(5,DAILY_NAME,'daily')):
        c.emit('NewObject',1);c.emit('LoadConstString',2,name);c.put(1,2,'name')
        c.string(2,'more');c.put(1,2,'type')
        c.string(2,'#25bcbf');c.put(1,2,'color')
        # IconFont F17938 has no statistics/report/calendar glyph. Keep these
        # two pills text-only instead of mislabelling them with another icon.
        c.emit('LoadConstFalse',2);c.put(1,2,'icon')
        c.string(2,key);c.put(1,2,'key')
        c.emit('PutOwnByIndex',0,1,index)
    c.emit('Ret',0)
    return c


def additions(reader,data,changed,changes):
    assert reader.header.version==90
    index=json.loads((Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    by_id={x['id']:x for x in index};retired=set();todo=[20053]
    while todo:
        fid=todo.pop()
        if fid in retired:continue
        retired.add(fid);todo.extend(by_id[fid]['f'])
    assert OPEN in retired
    assert all(x['id'] in retired for x in index if OPEN in x['f'])
    assert [x['id'] for x in index if SELECTED in x['f']]==[35703]
    for fid in (OPEN,SELECTED):
        assert [i for i,h in enumerate(reader.function_headers) if h.offset==reader.function_headers[fid].offset]==[fid]
    # Replace only the original navigation range; keep exception offsets and all
    # other branches exactly as they are for the subsequent history patch.
    h=reader.function_headers[35728]
    assert h.bytecodeSizeInBytes==906
    original=bytes(data[h.offset+0x355:h.offset+0x367])
    assert original[0]==ops.LoadFromEnvironment.opcode
    jump=C(reader);jump.emit('CreateClosure',6,5,OPEN);jump.emit('Call2',6,6,0,7);jump.emit('Ret',0)
    assert len(jump.raw)<=18
    data[h.offset+0x355:h.offset+0x367]=bytes(jump.raw)+bytes([ops.Unreachable.opcode])*(18-len(jump.raw))
    changed.add(35728)
    # The original row renderer keeps every original style and gesture. Only
    # the explicit boolean-false marker hides an icon; absent legacy markers
    # still execute the exact original SVG selection and creation instructions.
    icon=C(reader,reader.function_headers[35766].highestReadCacheIndex)
    icon.get(20,0,'icon');icon.emit('LoadConstFalse',21);hidden=icon.branch('JStrictEqual',20,21)
    row_header=reader.function_headers[35766]
    reader.file_buffer.seek(row_header.offset+0x95)
    icon.raw.extend(reader.file_buffer.read(0xbe-0x95))
    done=icon.branch('Jmp');icon.finish(hidden);icon.emit('LoadConstNull',14);icon.finish(done)
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    replacements={}
    for ins in parse_hbc_bytecode(row_header,reader):
        if 0x95<=ins.original_pos<0xbe:
            args=[getattr(ins,'arg'+str(i+1)) for i in range(len(ins.inst.operands))]
            replacements[ins.original_pos]=(ins.inst.name,args,bytes(icon.raw) if ins.original_pos==0x95 else b'')
    key=C(reader,icon.cache);key.get(20,0,'key');has_key=key.branch('JmpTrue',20)
    key.get(20,0,'type');key.finish(has_key);key.emit('Mov',0,20)
    replacements[0x155]=('GetByIdShort',[0,0,8,208],bytes(key.raw))
    row,_,_=rewrite(reader,35766,{},replacements)
    functions={OPEN:opener(reader),SELECTED:selected(reader),35770:footer(reader)}
    fids=(OPEN,SELECTED,35770,35766)
    result,placed=insert_bodies(reader,data,[bytes(functions[fid].raw) if fid in functions else row for fid in fids])
    small=reader.get_small_func_header_reader()
    for fid,location in zip(fids,placed):
        meta=small.from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(small))
        if meta.overflowed:meta=reader.get_large_func_header_reader().from_buffer(result,(meta.infoOffset<<16)|meta.offset)
        meta.offset,meta.bytecodeSizeInBytes=location
        meta.frameSize=max(meta.frameSize,32)
        if fid in (OPEN,SELECTED):
            meta.paramCount=2;meta.environmentSize=0;meta.hasExceptionHandler=0
            meta.highestReadCacheIndex=functions[fid].cache;meta.highestWriteCacheIndex=0
        elif fid==35770:
            meta.highestReadCacheIndex=0
        else:meta.highestReadCacheIndex=max(meta.highestReadCacheIndex,key.cache)
        changed.add(fid);del meta
    changes.append({'function':35728,'action':'more opens project-scoped assistant directory; two new pills reuse original styles and sendMessage'})
    return result
