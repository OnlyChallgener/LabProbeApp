"""Fixed original ToolXiaoxing entry with its artwork and click affordance.

The active patch retains the original constructor frame and removes feed and
carousel effects. Dynamic helper generators below are retired, not applied.
"""
import ctypes
from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_assistant_notifications import C as Base
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

STRINGS={38714:'bnFetch',38866:'bnOpen',
        # Banner copy. 344 was an unreferenced reanimated type name in the
        # original bundle: no function, literal buffer or identifier used it.
        344:'小星助手 V2.0'}
FOCUS,CLEAN,RESULT,MAP,CLICK,SELECTOR=20075,20076,20077,20078,20079,20072
HELPERS=(FOCUS,CLEAN,RESULT,MAP,CLICK,SELECTOR)
# Same Metro module id the audited speed-test entry uses for its store hooks.
REDUX=1505
# @react-navigation/core: its re-export factory lists useFocusEffect among the
# real exports; the page's own navigation dep only re-exports useNavigation.
NAVIGATION=1415

class C(Base):
    def sid(self,t):
        for s,v in STRINGS.items():
            if v==t:return s
        return super().sid(t)
    def native_banner(self,depth):
        self.emit('GetEnvironment',2,depth);self.emit('LoadFromEnvironment',2,2,4)
        self.get(2,2,'NativeModules');self.get(2,2,'sta')
    def end(self):
        for b in self.block:self.finish(b)
        self.emit('LoadConstUndefined',1);self.emit('Ret',1)

def fallback(r):
    c=C(r);c.emit('GetEnvironment',0,0);c.emit('NewObject',2)
    c.emit('CreateClosure',1,0,24149);c.put(2,1,'onClick')
    c.emit('LoadConstString',1,344);c.put(2,1,'content')
    c.emit('NewArray',3,1);c.emit('PutOwnByIndex',3,2,0);c.emit('Ret',3);return c

def focus(r):
    c=C(r);c.block=[];c.emit('CreateEnvironment',0);c.emit('LoadConstTrue',1);c.emit('StoreToEnvironment',0,0,1)
    c.emit('GetEnvironment',6,0);c.emit('LoadFromEnvironment',7,6,7)
    c.emit('LoadFromEnvironment',8,6,8);c.set(7,8,'scope')
    c.emit('LoadConstUndefined',1);c.emit('LoadConstZero',9)
    c.emit('LoadFromEnvironment',10,6,1);c.emit('Call2',10,10,1,9)
    c.emit('LoadFromEnvironment',9,6,0);c.emit('LoadFromEnvironment',10,6,4);c.emit('Call2',10,10,1,9)
    no_scope=c.branch('JmpFalseLong',8)
    c.native_banner(1);c.get(3,2,'bnFetch');c.emit('LoadFromEnvironment',4,6,6)
    c.emit('CreateClosure',5,0,RESULT);c.emit('Call3',3,3,2,4,5)
    c.finish(no_scope);c.emit('CreateClosure',1,0,CLEAN);c.emit('Ret',1);return c

def clean(r):
    c=C(r);c.emit('GetEnvironment',0,0);c.emit('LoadConstFalse',1);c.emit('StoreToEnvironment',0,0,1);c.emit('Ret',1);return c

def result(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0);c.emit('LoadFromEnvironment',1,0,0);c.block.append(c.branch('JmpFalseLong',1))
    c.emit('LoadConstUndefined',1);c.emit('GetEnvironment',6,1)
    c.native_banner(2);c.emit('LoadFromEnvironment',7,6,6);c.get(3,2,'assistantHistoryScope');c.emit('Call2',3,3,2,7)
    c.emit('LoadFromEnvironment',8,6,8);c.block.append(c.branch('JStrictNotEqualLong',3,8))
    c.emit('LoadParam',4,1);c.block.append(c.branch('JmpFalseLong',4));c.emit('GetGlobalObject',5);c.get(5,5,'JSON');c.get(3,5,'parse');c.emit('Call2',4,3,5,4)
    c.get(4,4,'items');c.get(5,4,'length');c.block.append(c.branch('JmpFalseLong',5))
    c.get(3,4,'map');c.emit('CreateClosure',5,6,MAP);c.emit('Call2',4,3,4,5)
    c.emit('LoadFromEnvironment',5,6,1);c.emit('LoadConstZero',7);c.emit('Call2',5,5,1,7)
    c.emit('LoadFromEnvironment',5,6,4);c.emit('Call2',5,5,1,4);c.end();return c

def maprow(r):
    c=C(r);c.emit('CreateEnvironment',0);c.emit('LoadParam',1,1);c.emit('StoreToEnvironment',0,0,1)
    c.emit('NewObject',2);c.get(3,1,'content');c.put(2,3,'content')
    c.emit('CreateClosure',3,0,CLICK);c.put(2,3,'onClick');c.emit('Ret',2);return c

def click(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0);c.emit('GetEnvironment',6,1);c.emit('LoadConstUndefined',1)
    c.native_banner(2);c.emit('LoadFromEnvironment',7,6,6);c.get(3,2,'assistantHistoryScope');c.emit('Call2',3,3,2,7)
    c.emit('LoadFromEnvironment',8,6,8);c.block.append(c.branch('JStrictNotEqualLong',3,8))
    c.emit('LoadFromEnvironment',9,6,7);c.get(9,9,'scope');c.block.append(c.branch('JStrictNotEqualLong',9,8))
    c.emit('LoadFromEnvironment',4,0,0);c.get(4,4,'id');c.get(3,2,'bnOpen');c.emit('Call3',3,3,2,7,4);c.end();return c

def selector(r):
    c=C(r);c.emit('LoadParam',0,1);c.get(0,0,'network');c.get(0,0,'curProject');c.emit('Ret',0);return c

def additions(reader,data,changed,changes):
    # One fixed official entry. Keep the original artwork and hit targets,
    # but never subscribe to the message feed or create a carousel timer.
    fixed=fallback(reader)
    empty=C(reader);empty.emit('LoadConstUndefined',0);empty.emit('Ret',0)
    title=C(reader,reader.function_headers[24141].highestReadCacheIndex)
    title.string(14,'小星助手 V2.0')
    badge=C(reader,title.cache);badge.emit('LoadConstNull',14)
    body,_,_=rewrite(reader,24141,{}, {
        0x418:('GetById',[14,16,37,21885],bytes(title.raw)),
        0x471:('Call3',[14,37,3,36,14],bytes(badge.raw))})
    raw={23460:bytes(fixed.raw),24142:bytes(empty.raw),24144:bytes(empty.raw),24141:body}
    result,placed=insert_bodies(reader,data,list(raw.values()));small=reader.get_small_func_header_reader()
    for fid,loc in zip(raw,placed):
        meta=small.from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(small));assert not meta.overflowed
        meta.offset,meta.bytecodeSizeInBytes=loc
        # Original Animated.Value Construct stages its implicit arguments at
        # frameSize-7. No new locals were introduced in this original render;
        # growing the frame alone makes it construct with a different `this`.
        if fid!=24141:meta.frameSize=max(meta.frameSize,16)
        if fid in (24142,24144):meta.highestReadCacheIndex=0;meta.highestWriteCacheIndex=0;meta.hasExceptionHandler=0
        if fid==24141:meta.highestReadCacheIndex=title.cache
        if fid==23460:meta.highestReadCacheIndex=fixed.cache
        changed.add(fid);del meta
    changes.append({'function':24141,'action':'fixed official assistant entry; no banner feed, carousel or interval'})
    return result

def _retired_dynamic_additions(reader,data,changed,changes):
    import hashlib,json
    from pathlib import Path
    from patch_bundle import load,SOURCE_SHA
    from patch_assistant_cards import assert_literal_ownership
    root=Path(__file__).resolve().parents[2]
    source=(root/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes();assert hashlib.sha256(source).hexdigest()==SOURCE_SHA
    original=load(source);index=json.loads((root/'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    by={x['id']:x for x in index};retired=set();todo=[20053]
    while todo:
        f=todo.pop()
        if f not in retired:retired.add(f);todo.extend(by[f]['f'])
    assert_literal_ownership(original,set(STRINGS),retired)
    for f in HELPERS:
        assert f in retired and all(x['id'] in retired for x in index if f in x['f'])
        # Empty retired callbacks can share their original bytes. Only this
        # header is redirected; aliases retain those untouched original bytes.
        if f in changed:
            from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
            h=reader.function_headers[f]
            current=bytes(data[h.offset:h.offset+h.bytecodeSizeInBytes])
            noop=bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0])
            assert current==noop+bytes([ops.Unreachable.opcode]*(len(current)-len(noop))),('banner helper already claimed by non-retired body',f)
    bodies={23460:fallback(reader),FOCUS:focus(reader),CLEAN:clean(reader),RESULT:result(reader),
            MAP:maprow(reader),CLICK:click(reader),SELECTOR:selector(reader)}
    raw={f:bytes(v.raw) for f,v in bodies.items()}
    # Retain original hooks; replace only the obsolete infoList filter effect.
    h=reader.function_headers[24141];ii=list(parse_hbc_bytecode(h,reader))
    # bridge initialization is a same-size replacement, original offsets remain.
    c=C(reader,h.highestReadCacheIndex)
    # The project comes from the store the page already renders with, asked
    # here rather than through the Tool model: growing a live function moves
    # its body, and a moved body stops behaving like the original one.
    c.emit('GetEnvironment',40,0);c.emit('LoadFromEnvironment',41,40,0)
    c.number(42,REDUX);c.emit('LoadConstUndefined',43);c.emit('Call2',40,41,43,42)
    c.get(40,40,'useSelector');c.emit('CreateClosure',41,15,SELECTOR);c.emit('Call2',40,40,43,41)
    c.get(40,40,'projectId');c.emit('StoreToEnvironment',15,6,40)
    c.get(41,6,'useRef');c.emit('NewObject',42);c.emit('Call2',41,41,3,42);c.get(41,41,'current');c.emit('StoreToEnvironment',15,7,41)
    c.get(42,7,'NativeModules');c.get(42,42,'sta')
    c.get(43,42,'assistantHistoryScope');c.emit('Call2',43,43,42,40);c.emit('StoreToEnvironment',15,8,43)
    # The navigation dep re-export (dep7=module2040) only has useNavigation.
    # useFocusEffect lives in the audited core module, exported as a real
    # function, so require that module id instead of the dep's narrow shim.
    c.number(42,NAVIGATION);c.emit('LoadConstUndefined',43);c.emit('Call2',42,14,43,42)
    c.get(42,42,'useFocusEffect');c.get(43,6,'useCallback');c.emit('CreateClosure',44,15,FOCUS)
    c.emit('NewArray',45,1);c.emit('LoadFromEnvironment',46,15,8);c.emit('PutOwnByIndex',45,46,0)
    c.emit('Call3',43,43,3,44,45);c.emit('Call2',42,42,3,43)
    # Remove original infoList filter effect (0xf4..0x10c).
    replacements={i.original_pos:(i.inst.name,[getattr(i,'arg'+str(k+1)) for k in range(len(i.inst.operands))],bytes(c.raw) if i.original_pos==0xf4 else b'') for i in ii if 0xf4<=i.original_pos<0x10c}
    # The banner keeps the official artwork (xiaoxing avatar, chat bubble,
    # arrow) but the copy is the hub product name and the original survey
    # badge icon is dropped: the hub banner is not a feedback questionnaire.
    copy_code=C(reader,c.cache);copy_code.string(14,'小星助手 V2.0')
    replacements[0x418]=('GetById',[14,16,37,21885],bytes(copy_code.raw))
    badge_code=C(reader,c.cache);badge_code.emit('LoadConstNull',14)
    replacements[0x471]=('Call3',[14,37,3,36,14],bytes(badge_code.raw))
    guard=C(reader,c.cache);guard.emit('LoadFromEnvironment',40,15,7);guard.get(40,40,'scope');guard.emit('LoadFromEnvironment',41,15,8)
    same=guard.branch('JStrictEqual',40,41);guard.emit('LoadFromEnvironment',2,15,0);guard.emit('LoadConstZero',4);guard.finish(same)
    banner,_,_=rewrite(reader,24141,{0x128:bytes(guard.raw)},replacements)
    raw[24141]=banner
    resultdata,placed=insert_bodies(reader,data,list(raw.values()));small=reader.get_small_func_header_reader()
    for fid,loc in zip(raw,placed):
        m=small.from_buffer(resultdata,reader.functions_offset+fid*ctypes.sizeof(small));assert not m.overflowed
        m.offset,m.bytecodeSizeInBytes=loc
        # Hermes sizes a frame as locals plus the widest outgoing call; every
        # untouched function in this bundle satisfies that. The carousel block
        # reaches r46 and calls with three arguments, so 24141 needs 50.
        if fid in bodies:m.frameSize=max(m.frameSize,70)
        if fid==24141:m.frameSize=max(m.frameSize,56)
        if fid in bodies:m.highestReadCacheIndex=bodies[fid].cache;m.highestWriteCacheIndex=len(bodies[fid].writes)
        if fid in HELPERS:m.paramCount=2;m.environmentSize=1 if fid in (FOCUS,MAP) else 0;m.hasExceptionHandler=0
        if fid==24141:m.environmentSize=9;m.highestReadCacheIndex=guard.cache;m.highestWriteCacheIndex=max(m.highestWriteCacheIndex,len(c.writes))
        changed.add(fid);del m
    changes.append({'function':24141,'action':'original carousel uses focused project and authenticated local item clicks; no global closure cache'})
    return resultdata
