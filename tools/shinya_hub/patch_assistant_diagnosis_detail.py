"""Keep original assistant DiagnoseDetail; explicitly render unknown evidence.

No firmware Signal diagnosis changes. No new functions or private string slots.
The retained DiagnoseItem supplies original layout/fonts; level-zero entries
never call their typed detail renderer, and unknown values use neutral text.
"""
import ctypes
from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

STRINGS={}
CALLBACKS=(33247,33249,33251,33253,33255)
FIDS=CALLBACKS+(33267,33269,33271,33277,33278,33280)

class C(Code):
    def __init__(self,r,cache=0):super().__init__(r);self.cache=cache
    def sid(self,s):return 12897 if s=='未检测' else super().sid(s)
    def get(self,d,o,s):self.cache+=1;self.emit('GetById',d,o,self.cache,self.sid(s))

def guard(r,fid):
    c=C(r,r.function_headers[fid].highestReadCacheIndex)
    if fid == 33247:
        # The assistant no longer includes the phone/local probe row. Check
        # the typed contract, never the translated display label.
        c.emit('LoadParam',40,1);c.get(41,40,'detailData')
        absent=c.branch('JmpFalseLong',41)
        c.get(42,41,'problemType');c.string(43,'sta_ping')
        keep=c.branch('JStrictNotEqual',42,43)
        c.emit('LoadConstNull',44);c.emit('Ret',44)
        c.finish(keep);c.finish(absent)
    c.emit('LoadParam',40,1);c.get(41,40,'detailData');missing=c.branch('JmpFalseLong',41)
    c.emit('Mov',42,41);c.get(41,41,'problemLevel');known=c.branch('JmpTrueLong',41)
    # A falsy level means "checked, no exception" in the official cloud.
    # Only the server's short healthy verdict "状况良好" is allowed in the
    # compact row; any other (possibly long technical) problem text stays
    # '--' so this slot never turns into a paragraph. Genuinely unknown
    # evidence keeps '--' as well. The guard still owns isShowProblem /
    # isShowFold / childView, so the row stays closed: no expandable panel
    # full of renderer defaults that the cloud never returned.
    c.get(48,42,'problem');c.string(49,'状况良好')
    invalid=c.branch('JStrictNotEqual',48,49)
    chosen=c.branch('JmpLong');c.finish(invalid)
    c.string(48,'--');c.finish(chosen)
    c.emit('GetEnvironment',41,2);c.emit('LoadFromEnvironment',42,41,0)
    c.emit('LoadFromEnvironment',43,41,1);c.number(44,16);c.emit('GetByVal',43,43,44)
    c.emit('LoadConstUndefined',45);c.emit('Call2',43,42,45,43);c.get(43,43,'jsx')
    c.emit('LoadFromEnvironment',42,41,13)
    c.emit('NewObject',44);c.get(46,40,'name');c.put(44,46,'name')
    # This original value slot is a compact reading. Unknown/partial evidence
    # stays in metadata; never place a technical sentence beside its label.
    c.emit('NewObject',46);c.put(46,48,'text')
    c.emit('LoadConstFalse',47);c.put(46,47,'isProblem')
    c.emit('NewArray',48,1);c.emit('PutOwnByIndex',48,46,0);c.put(44,48,'showText')
    c.put(44,47,'isShowProblem');c.put(44,47,'isShowFold')
    c.emit('LoadConstNull',48);c.put(44,48,'childView')
    c.emit('Call3',44,43,45,42,44);c.emit('Ret',44)
    c.finish(missing)
    c.emit('LoadConstNull',44);c.emit('Ret',44);c.finish(known)
    return c

def additions(reader,data,changed,changes):
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    plans={};caches={}
    # The V1 subtitle adds only normal+abnormal counts, so observed-only IoT
    # and radio checks appeared as zero. Use the server's explicit measured /
    # assessed / unavailable sentence in the same original Text style.
    from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
    c=C(reader,reader.function_headers[35955].highestReadCacheIndex)
    # Persisted V1/older V2 messages have only the original counters. Falling
    # back to the original block keeps their subtitles visible as well.
    c.get(40,0,'stepSubTitle');legacy=c.branch('JmpFalseLong',40)
    c.emit('NewObject',1);c.put(1,12,'style');c.put(1,40,'children')
    c.emit('Call3',10,11,4,10,1)
    # The skipped original count block also restored values used by the row
    # tail. Keep those live bindings: RN, Metro deps and require. Without this
    # r12 remains the style object and the later require(...) call throws.
    c.emit('LoadFromEnvironment',1,9,6)
    c.emit('LoadFromEnvironment',11,9,1)
    c.emit('LoadFromEnvironment',12,9,0)
    # Skip the old count expression without deleting its branches: rewrite
    # still relocates every original target, including the retained row tail.
    c.emit('JmpLong',0x284-0x16b+5)
    c.finish(legacy)
    plans[35955]=rewrite(reader,35955,{0x16b:bytes(c.raw)},{})[0];caches[35955]=c.cache
    # Old malformed diagnosis messages may already exist in persisted history.
    # Run all original hooks first, then hide only a recommendation with no
    # actual problem or solution. Do not render its fixed-height empty shell.
    c=C(reader,reader.function_headers[35966].highestReadCacheIndex)
    absent=c.branch('JmpFalseLong',17)
    c.get(40,17,'problem');problem=c.branch('JmpTrueLong',40)
    c.get(40,17,'problemSolution');solution=c.branch('JmpTrueLong',40)
    c.finish(absent);c.emit('LoadConstNull',40);c.emit('Ret',40)
    c.finish(problem);c.finish(solution)
    plans[35966]=rewrite(reader,35966,{0x174:bytes(c.raw)},
        {0x194:('PutNewOwnByIdShort',[7,5,145],b'')})[0];caches[35966]=c.cache
    for fid in CALLBACKS:
        c=guard(reader,fid);plans[fid]=rewrite(reader,fid,{0:bytes(c.raw)},{})[0];caches[fid]=c.cache
    # Original falsy defaults turn unavailable duration/time/count into zero.
    # Preserve legitimate zero by checking null/undefined at the getter, rather
    # than changing a ||0 fallback that also handles real zero.
    fields={33267:{'duration','durationTime'},33269:{'duration','lastTime'},33271:{'shakeCnt','lastShakeTime'}}
    for fid,names in fields.items():
        ins=list(parse_hbc_bytecode(reader.function_headers[fid],reader));insertions={};cache=reader.function_headers[fid].highestReadCacheIndex
        for n,i in enumerate(ins):
            if i.inst.name not in ('GetById','GetByIdShort') or reader.strings[i.arg4] not in names:continue
            if i.arg2!=1:continue
            nxt=ins[n+1];fallback=ins[n+2]
            assert nxt.inst.name=='JmpTrue'
            c=C(reader,cache)
            # r40 holds whether the original read was null/undefined. The
            # existing fallback then becomes an explicit unknown string.
            c.emit('LoadConstNull',40);b=c.branch('JEqual',i.arg1,40)
            original_jump=c.branch('JmpLong');c.finish(b)
            c.string(i.arg1,'--');c.finish(original_jump)
            # Insert directly after the read: unknown is now truthy and skips
            # the old fallback, while a real zero follows original ||0 path.
            insertions[nxt.original_pos]=bytes(c.raw)
        plans[fid]=rewrite(reader,fid,insertions,{})[0];caches[fid]=cache
    # A missing category level used to default to the green "normal" badge.
    # Retain the original image for known values, otherwise original Text style.
    c=C(reader,reader.function_headers[33277].highestReadCacheIndex)
    c.emit('Mov',40,5)
    b=C(reader,c.cache);known=b.branch('JmpTrueLong',40)
    b.emit('GetEnvironment',41,0);b.emit('LoadFromEnvironment',41,41,0);b.get(42,41,'commonDetailtext')
    b.emit('NewObject',43);b.string(44,'#999999');b.put(43,44,'color')
    b.emit('NewArray',44,2);b.emit('PutOwnByIndex',44,42,0);b.emit('PutOwnByIndex',44,43,1)
    b.emit('NewObject',5);b.put(5,44,'style');b.string(44,'--');b.put(5,44,'children')
    b.emit('GetEnvironment',41,1);b.emit('LoadFromEnvironment',41,41,5);b.get(10,41,'Text')
    b.emit('Call3',5,11,4,10,5);done=b.branch('JmpLong');b.finish(known)
    b.emit('Call3',5,11,4,10,5);b.finish(done)
    plans[33277]=rewrite(reader,33277,{0x13e:bytes(c.raw)},{0x15a:('Call3',[5,11,4,10,5],bytes(b.raw))})[0];caches[33277]=b.cache
    # Bound the entire result group to the card, then wrap sibling readings
    # together. Per-Text flexGrow stretched 5G to a full line and pushed 2.4G
    # outside the card even though each individual Text was shrinkable.
    c=C(reader)
    c.number(40,1);c.put(27,40,'flexShrink')
    c.number(40,0);c.put(27,40,'minWidth')
    c.string(40,'wrap');c.put(27,40,'flexWrap')
    outer=C(reader);outer.emit('NewObject',40)
    outer.number(41,1);outer.put(40,41,'flexShrink')
    outer.number(41,0);outer.put(40,41,'minWidth');outer.put(4,40,'style')
    plans[33278]=rewrite(reader,33278,{0x1be:bytes(c.raw),0x1e7:bytes(outer.raw)},{})[0]
    caches[33278]=reader.function_headers[33278].highestReadCacheIndex
    # Only unavailable row text receives a neutral colour; measured original
    # green/orange styling and typography remain the same.
    c=C(reader,reader.function_headers[33280].highestReadCacheIndex)
    c.string(40,'未检测');other=c.branch('JStrictNotEqual',0,40)
    c.emit('NewObject',40);c.string(41,'#999999');c.put(40,41,'color');c.emit('PutOwnByIndex',5,40,2);c.finish(other)
    # The original value Text sits beside a short label. New measured evidence
    # can be a full sentence; constrain the value to the remaining row width so
    # React Native wraps it instead of painting beyond the white card.
    wrap=C(reader,c.cache)
    wrap.emit('NewObject',40)
    wrap.number(41,1);wrap.put(40,41,'flexShrink')
    wrap.number(41,0);wrap.put(40,41,'flexGrow')
    wrap.number(41,0);wrap.put(40,41,'minWidth')
    wrap.emit('PutOwnByIndex',5,40,3)
    grow=C(reader);grow.emit('NewArray',5,4)
    plans[33280]=rewrite(reader,33280,{0x71:bytes(c.raw)+bytes(wrap.raw)},
        {0x3f:('NewArray',[5,3],bytes(grow.raw))})[0];caches[33280]=wrap.cache
    result,placed=insert_bodies(reader,data,list(plans.values()));small=reader.get_small_func_header_reader()
    for fid,loc in zip(plans,placed):
        m=small.from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(small));assert not m.overflowed
        m.offset,m.bytecodeSizeInBytes=loc;m.frameSize=max(m.frameSize,80);m.highestReadCacheIndex=caches[fid]
        changed.add(fid);del m
    changes.append({'function':33247,'action':'original assistant diagnosis unknown entries are neutral, not expandable; preserve typed measured V1 renderers'})
    return result

def _raw(reader,ins):
    # Preserve branch relocation: rewrite owns the original JmpTrue address.
    c=C(reader);c.emit(ins.inst.name,*[getattr(ins,'arg'+str(n+1)) for n in range(len(ins.inst.operands))]);return bytes(c.raw)
