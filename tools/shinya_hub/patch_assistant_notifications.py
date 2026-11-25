"""Journal-backed notification navigation and original Helper chat integration.

Apply after assistant_history. Native returns only current HUB/account journal
entries; event payloads are deliberately ignored and re-read through that gate.
No UI renderer, deep link, account switch or remote settings are introduced.
"""
import ctypes
import json
from pathlib import Path

from hbc_grow import insert_bodies
from patch_terminal_ipv6 import Code
from patch_measured_status import rewrite
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

STRINGS = {12212:'nPending',37844:'nMerge',
           40904:'nOpened',39367:'nClear',
           50696:'historyScope',35510:'accessId',43237:'HubNoticeOpen',
           35399:'HubNoticeMessages'}
PROVIDER_EFFECT, PROVIDER_TICK, PROVIDER_CLEAN = 20063,20064,20066
CHAT_EFFECT, CHAT_TICK, CHAT_CLEAN, SCROLL_FAILED, VIEWABLE = 20067,20070,20071,20073,20074
AUTOSCROLL = 20080
HELPERS=(PROVIDER_EFFECT,PROVIDER_TICK,PROVIDER_CLEAN,CHAT_EFFECT,CHAT_TICK,CHAT_CLEAN,SCROLL_FAILED,VIEWABLE,AUTOSCROLL)

class C(Code):
    def __init__(self,r,cache=0):super().__init__(r);self.cache=cache;self.writes={}
    def sid(self,t):
        if t=='assistantHistoryScope':return 30092
        for sid,value in STRINGS.items():
            if value==t:return sid
        return super().sid(t)
    def get(self,d,o,t):
        sid=self.sid(t)
        # Retired literal slots are not imported into Hermes' identifier map.
        # GetById on such a slot resolves the wrong/empty SymbolID even though
        # reader.strings[sid] and the native method name look identical.
        if int(self.reader.string_kinds[sid])!=1:
            assert d!=o, (t,'dynamic property key must not overwrite its object')
            self.emit('LoadConstString',d,sid);self.emit('GetByVal',d,o,d)
        else:
            self.cache+=1;self.emit('GetById',d,o,self.cache,sid)
    def set(self,o,v,t):
        sid=self.sid(t);slot=self.writes.setdefault(sid,len(self.writes)+1)
        self.emit('PutById',o,v,slot,sid)
    def native(self,kind):
        # Slot 0 of the owning Metro module environment is its local require;
        # React Native is dependency 18 in this audited bundle. Provider and
        # Helper state slots are ordinary component values, never bridge APIs.
        self.emit('GetEnvironment',2,1)
        self.emit('LoadFromEnvironment',3,2,0)
        self.number(4,18);self.emit('LoadConstUndefined',5)
        self.emit('Call2',2,3,5,4)
        self.get(2,2,'NativeModules');self.get(2,2,'sta')
    def pending(self):
        self.get(3,2,'nPending')
        # A missing optional native export must never enter the root page's
        # ErrorBoundary. Log only bridge method names (no account/event data).
        self.emit('TypeOf',18,3);self.string(19,'function')
        available=self.branch('JStrictEqualLong',18,19)
        self.emit('GetGlobalObject',18);self.get(19,18,'Object');self.get(20,19,'keys')
        self.emit('Call2',20,20,19,2)
        self.get(19,18,'console');self.get(21,19,'warn');self.string(22,'nPending')
        self.emit('Call3',21,21,19,22,20)
        self.emit('LoadConstUndefined',1);self.emit('Ret',1)
        self.finish(available);self.emit('Call1',3,3,2)
        self.block.append(self.branch('JmpFalseLong',3))
        self.emit('GetGlobalObject',4);self.get(4,4,'JSON');self.get(5,4,'parse')
        self.emit('Call2',3,5,4,3)
    def end(self):
        for b in self.block:self.finish(b)
        self.emit('LoadConstUndefined',1);self.emit('Ret',1)

def effect(r,provider):
    c=C(r);c.emit('CreateEnvironment',0);c.emit('GetEnvironment',6,0)
    c.emit('LoadConstUndefined',1)
    c.emit('CreateClosure',7,6,PROVIDER_TICK if provider else CHAT_TICK)
    c.emit('Call1',8,7,1)
    # Resolve DeviceEventEmitter through the audited React Native import.
    # Component environment slots hold state and callbacks; reading them as an
    # event bus caused the startup TypeError in commitHookEffectListMount.
    c.emit('GetEnvironment',2,1);c.emit('LoadFromEnvironment',3,2,0)
    c.number(4,18);c.emit('LoadConstUndefined',5);c.emit('Call2',2,3,5,4)
    c.get(2,2,'DeviceEventEmitter')
    c.get(3,2,'addListener')
    c.string(4,'HubNoticeOpen');c.emit('Call3',5,3,2,4,7)
    c.emit('StoreToEnvironment',0,0,5)
    if not provider:
        c.string(4,'HubNoticeMessages');c.emit('Call3',5,3,2,4,7)
        c.emit('StoreToEnvironment',0,1,5)
    c.emit('CreateClosure',1,0,PROVIDER_CLEAN if provider else CHAT_CLEAN);c.emit('Ret',1)
    return c

def cleanup(r,provider):
    c=C(r);c.emit('GetEnvironment',0,0)
    for slot in range(1 if provider else 2):
        c.emit('LoadFromEnvironment',2,0,slot);c.get(3,2,'remove');c.emit('Call1',3,3,2)
    c.emit('LoadConstUndefined',1);c.emit('Ret',1);return c

def provider_tick(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0);c.emit('LoadConstUndefined',1);c.native('provider');c.pending()
    c.get(6,3,'projectId');c.emit('AddEmptyString',6,6)
    c.get(7,3,'historyScope');c.block.append(c.branch('JmpFalseLong',7))
    c.emit('LoadFromEnvironment',8,0,8);c.block.append(c.branch('JmpFalseLong',8))
    c.get(9,8,'length');c.emit('LoadConstZero',10);c.emit('LoadConstUndefined',11)
    loop=len(c.raw);done=c.branch('JNotLessLong',10,9)
    c.emit('GetByVal',12,8,10);c.get(13,12,'projectId');c.emit('AddEmptyString',13,13)
    hit=c.branch('JStrictEqual',13,6)
    c.get(13,12,'accessId');c.emit('AddEmptyString',13,13);miss=c.branch('JStrictNotEqual',13,6)
    c.finish(hit);c.block.append(c.branch('JmpTrueLong',11));c.emit('Mov',11,12)
    c.finish(miss);c.emit('Inc',10,10);back=c.branch('JmpLong');_back(c,back,loop);c.finish(done)
    c.block.append(c.branch('JmpFalseLong',11))
    # The UI's own projectId is supplied to both the real setter and scope gate.
    c.get(12,11,'projectId');c.get(13,2,'assistantHistoryScope');c.emit('Call2',13,13,2,12)
    c.block.append(c.branch('JStrictNotEqualLong',13,7))
    c.emit('LoadFromEnvironment',14,0,5);switch=c.branch('JmpFalse',14)
    c.get(14,14,'projectId');c.emit('AddEmptyString',14,14);c.emit('AddEmptyString',15,12)
    same=c.branch('JStrictEqual',14,15)
    c.finish(switch);c.emit('LoadFromEnvironment',14,0,17);c.emit('Call2',14,14,1,11)
    c.emit('Ret',1)
    c.finish(same)
    c.get(12,3,'eventId');c.block.append(c.branch('JmpFalseLong',12))
    c.emit('LoadFromEnvironment',13,0,18);c.get(14,13,'current')
    c.block.append(c.branch('JStrictEqualLong',14,12))
    c.emit('LoadFromEnvironment',14,0,7);c.get(15,14,'navigate');c.string(16,'Helper')
    c.emit('Call2',15,15,14,16);c.set(13,12,'current');c.end();return c

def _back(c,pos,target):
    import struct
    struct.pack_into('<i',c.raw,pos+1,target-pos)

def chat_tick(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0);c.emit('LoadConstUndefined',1)
    c.emit('LoadFromEnvironment',6,0,35);c.get(7,6,'ready');c.block.append(c.branch('JmpFalseLong',7))
    c.emit('LoadFromEnvironment',8,0,36);c.get(7,6,'scope');c.block.append(c.branch('JStrictNotEqualLong',7,8))
    c.block.append(c.branch('JmpFalseLong',8));c.native('chat')
    c.emit('LoadFromEnvironment',9,0,23);c.get(3,2,'assistantHistoryScope');c.emit('Call2',3,3,2,9)
    c.block.append(c.branch('JStrictNotEqualLong',3,8))
    c.emit('GetGlobalObject',4);c.get(4,4,'JSON');c.get(5,4,'stringify')
    c.emit('LoadFromEnvironment',6,0,9);c.emit('Call2',6,5,4,6)
    c.get(3,2,'nMerge');c.emit('Call3',3,3,2,9,6)
    unchanged=c.branch('JmpFalse',3)
    c.get(5,4,'parse');c.emit('Call2',3,5,4,3)
    c.emit('LoadFromEnvironment',5,0,10);c.emit('Call2',5,5,1,3);c.emit('Ret',1)
    c.finish(unchanged);c.emit('CreateClosure',3,0,35702);c.emit('Call1',3,3,1);c.end();return c

def focus(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0);c.emit('LoadConstUndefined',1);c.native('chat')
    c.emit('LoadFromEnvironment',6,0,35);c.get(7,6,'ready');c.block.append(c.branch('JmpFalseLong',7))
    c.get(7,6,'scope');c.emit('LoadFromEnvironment',8,0,36);c.block.append(c.branch('JStrictNotEqualLong',7,8))
    c.get(3,2,'nPending');c.emit('Call1',3,3,2)
    no_pending=c.branch('JmpFalseLong',3)
    c.emit('GetGlobalObject',4);c.get(4,4,'JSON');c.get(5,4,'parse');c.emit('Call2',3,5,4,3)
    c.get(6,3,'historyScope');c.emit('LoadFromEnvironment',7,0,36)
    c.block.append(c.branch('JStrictNotEqualLong',6,7))
    c.get(6,3,'eventId');c.emit('LoadFromEnvironment',15,0,35);c.get(16,15,'uuid');known=c.branch('JStrictEqual',16,6)
    c.set(15,6,'uuid');c.emit('LoadConstZero',16);c.set(15,16,'count');c.finish(known)
    # Native only exposes a query from its current authenticated banner cache.
    # Gate synchronously in the existing stable hydration ref before original
    # sendMessage(question,'chat'); nOpened clears the transient pending action.
    c.get(17,3,'question');notice=c.branch('JmpFalseLong',17)
    c.get(18,15,'eventId');c.block.append(c.branch('JStrictEqualLong',18,6))
    c.set(15,6,'eventId');c.emit('LoadFromEnvironment',18,0,33);c.string(19,'chat')
    c.emit('Call3',18,18,1,17,19);c.get(18,2,'nOpened');c.emit('Call2',18,18,2,6);c.emit('Ret',1)
    c.finish(notice)
    c.emit('LoadFromEnvironment',8,0,9);c.get(9,8,'length');c.emit('LoadConstZero',10)
    loop=len(c.raw);c.block.append(c.branch('JNotLessLong',10,9))
    c.emit('GetByVal',11,8,10);c.get(11,11,'uuid');match=c.branch('JStrictEqual',11,6)
    c.emit('Inc',10,10);b=c.branch('JmpLong');_back(c,b,loop);c.finish(match)
    c.emit('LoadFromEnvironment',11,0,18);c.get(11,11,'current');c.block.append(c.branch('JmpFalseLong',11))
    c.get(12,11,'scrollToIndex');c.emit('NewObject',13);c.put(13,10,'index')
    c.emit('LoadConstFalse',14);c.put(13,14,'animated');c.emit('LoadConstDouble',14,0.5);c.put(13,14,'viewPosition')
    c.emit('Call2',12,12,11,13);c.emit('Ret',1)
    c.finish(no_pending)
    c.emit('LoadFromEnvironment',11,0,18);c.get(11,11,'current');c.block.append(c.branch('JmpFalseLong',11))
    c.get(12,11,'scrollToEnd');c.emit('Call1',12,12,11);c.end();return c

def failure(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0);c.emit('LoadConstUndefined',1)
    # FlatList invokes this for unmeasured distant rows. One approximate jump
    # materializes them; later size callbacks retry and viewability alone acks.
    c.emit('LoadParam',2,1);c.get(3,2,'averageItemLength');c.get(4,2,'index');c.emit('Mul',3,3,4)
    c.emit('LoadFromEnvironment',5,0,18);c.get(5,5,'current');c.block.append(c.branch('JmpFalseLong',5))
    c.get(6,5,'scrollToOffset');c.emit('NewObject',7);c.put(7,3,'offset');c.emit('LoadConstFalse',8);c.put(7,8,'animated')
    c.emit('Call2',6,6,5,7)
    c.emit('LoadFromEnvironment',8,0,35);c.get(9,8,'count');c.emit('LoadConstUInt8',10,3)
    c.block.append(c.branch('JNotLessLong',9,10));c.emit('Inc',9,9);c.set(8,9,'count')
    c.emit('GetGlobalObject',9);c.get(9,9,'setTimeout');c.emit('CreateClosure',10,0,35702);c.number(11,100)
    c.emit('Call3',9,9,1,10,11);c.end();return c

def command_scroll(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0)
    c.emit('LoadFromEnvironment',1,0,35);c.get(2,1,'ready');c.block.append(c.branch('JmpFalseLong',2))
    c.get(2,1,'scope');c.emit('LoadFromEnvironment',3,0,36);c.block.append(c.branch('JStrictNotEqualLong',2,3))
    c.emit('LoadFromEnvironment',4,0,18);c.block.append(c.branch('JmpFalseLong',4))
    c.get(4,4,'current');c.block.append(c.branch('JmpFalseLong',4))
    c.get(5,4,'scrollToEnd');c.emit('NewObject',6);c.emit('LoadConstTrue',7);c.put(6,7,'animated')
    c.emit('Call2',5,5,4,6);c.end();return c

def viewable(r):
    c=C(r);c.block=[];c.emit('GetEnvironment',0,0);c.emit('LoadConstUndefined',1);c.native('chat');c.pending()
    c.get(6,3,'historyScope');c.emit('LoadFromEnvironment',7,0,35);c.get(7,7,'scope');c.block.append(c.branch('JStrictNotEqualLong',6,7))
    c.get(6,3,'eventId');c.emit('LoadParam',8,1);c.get(8,8,'viewableItems');c.get(9,8,'length');c.emit('LoadConstZero',10)
    loop=len(c.raw);c.block.append(c.branch('JNotLessLong',10,9))
    c.emit('GetByVal',11,8,10);c.get(11,11,'item');c.get(12,11,'historyScope');wrong_scope=c.branch('JStrictNotEqual',12,7)
    c.get(11,11,'uuid');hit=c.branch('JStrictEqual',11,6);c.finish(wrong_scope)
    c.emit('Inc',10,10);b=c.branch('JmpLong');_back(c,b,loop);c.finish(hit)
    c.get(12,2,'nOpened');c.emit('Call2',12,12,2,6);c.end();return c

def additions(reader,data,changed,changes):
    assert reader.header.version==90
    # Full original ownership, including literals, is verified before using
    # private slots. Each new body moves only its own non-aliased header.
    from patch_bundle import load
    from patch_assistant_cards import assert_literal_ownership
    root=Path(__file__).resolve().parents[2]
    original=load((root/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
    index=json.loads((root/'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    by={x['id']:x for x in index};retired=set();todo=[20053]
    while todo:
        f=todo.pop()
        if f in retired:continue
        retired.add(f);todo.extend(by[f]['f'])
    for f in HELPERS:
        assert f in retired
        assert all(x['id'] in retired for x in index if f in x['f'])
        assert sum(h.offset==original.function_headers[f].offset for h in original.function_headers)==1
    for s in STRINGS:assert all(x['id'] in retired for x in index if s in x['s'])
    assert_literal_ownership(original,set(STRINGS),retired)
    bodies={PROVIDER_EFFECT:effect(reader,True),PROVIDER_TICK:provider_tick(reader),PROVIDER_CLEAN:cleanup(reader,True),
            CHAT_EFFECT:effect(reader,False),CHAT_TICK:chat_tick(reader),CHAT_CLEAN:cleanup(reader,False),
            SCROLL_FAILED:failure(reader),VIEWABLE:viewable(reader),AUTOSCROLL:command_scroll(reader),35702:focus(reader)}
    # User-submitted commands must reveal the appended message even when the
    # user is reading old rows. Wait for React's state commit, then use the
    # current list ref. This does not alter background notification targeting.
    send=C(reader,reader.function_headers[35743].highestReadCacheIndex)
    send.emit('GetEnvironment',40,0);send.emit('GetGlobalObject',41);send.get(42,41,'setTimeout')
    send.emit('CreateClosure',43,40,AUTOSCROLL);send.number(44,80);send.emit('LoadConstUndefined',45)
    send.emit('Call3',42,42,45,43,44)
    sendbody,_,_=rewrite(reader,35743,{0xdb:bytes(send.raw),0x183:bytes(send.raw)},{})
    # Provider's original r8 is the actual setCurProject callback; env18 holds
    # a stable ref so multiple pending events don't repeatedly navigate.
    c=C(reader,reader.function_headers[21967].highestReadCacheIndex)
    c.emit('StoreToEnvironment',5,17,8)
    c.emit('LoadFromEnvironment',28,1,11);c.get(29,28,'useRef');c.emit('Call1',29,29,3)
    c.emit('StoreToEnvironment',5,18,29);c.get(29,28,'useEffect')
    c.emit('CreateClosure',30,5,PROVIDER_EFFECT);c.emit('NewArray',31,4)
    for k,s in enumerate((5,8,6,17)):
        c.emit('LoadFromEnvironment',32,5,s);c.emit('PutOwnByIndex',31,32,k)
    c.emit('Call3',30,29,3,30,31)
    provider,_,_=rewrite(reader,21967,{0x52e:bytes(c.raw)},{});provider_cache=c.cache
    # Helper run after original hydration/save hooks. A stable viewability
    # callback is required by VirtualizedList; useRef preserves its identity.
    c=C(reader,reader.function_headers[35695].highestReadCacheIndex)
    c.get(61,1,'useRef');c.emit('CreateClosure',62,17,VIEWABLE);c.emit('Call2',63,61,3,62)
    c.get(63,63,'current');c.emit('StoreToEnvironment',17,37,63)
    c.get(61,1,'useEffect');c.emit('CreateClosure',62,17,CHAT_EFFECT);c.emit('NewArray',63,2)
    for k,s in enumerate((9,36)):
        c.emit('LoadFromEnvironment',64,17,s);c.emit('PutOwnByIndex',63,64,k)
    c.emit('Call3',62,61,3,62,63)
    # Locate insertion from actual original render instruction, after history
    # relocation: no assumptions about history's grown byte offsets.
    ins=list(parse_hbc_bytecode(reader.function_headers[35695],reader))
    hook=next(i.original_pos for i in ins if i.inst.name=='GetByIdShort' and i.arg4==245 and i.original_pos>0x290)
    flat=next(i.original_pos for i in ins if i.inst.name=='PutNewOwnById' and i.arg3==40184)
    f=C(reader,c.cache);f.emit('CreateClosure',65,17,SCROLL_FAILED);f.put(14,65,'onScrollToIndexFailed')
    f.emit('LoadFromEnvironment',65,17,37);f.put(14,65,'onViewableItemsChanged')
    helper,_,_=rewrite(reader,35695,{hook:bytes(c.raw),next(i.original_pos for i in ins if i.original_pos>flat):bytes(f.raw)},{})
    # Manual clear also hides this project's journal (other projects retained).
    clearins=list(parse_hbc_bytecode(reader.function_headers[35728],reader))
    site=next(i.original_pos for i in clearins if i.inst.name=='LoadFromEnvironment' and (i.arg1,i.arg2,i.arg3)==(8,5,10))
    clear=C(reader,reader.function_headers[35728].highestReadCacheIndex)
    clear.emit('GetEnvironment',60,1);clear.emit('LoadFromEnvironment',60,60,6)
    clear.get(60,60,'NativeModules');clear.get(60,60,'sta');clear.get(61,60,'nClear')
    clear.emit('LoadFromEnvironment',62,5,23);clear.emit('Call2',61,61,60,62)
    allowed=clear.branch('JmpTrue',61);clear.emit('LoadConstUndefined',61);clear.emit('Ret',61);clear.finish(allowed)
    # Preserve relocated exception handlers for the shortcut dispatcher.
    from patch_assistant_history import _rewrite
    clearbody,locations,_=_rewrite(reader,35728,{site:bytes(clear.raw)},{})
    raw={f:bytes(v.raw) for f,v in bodies.items()};raw.update({21967:provider,35695:helper,35728:clearbody,35743:sendbody})
    result,placed=insert_bodies(reader,data,list(raw.values()))
    # EH must be appended separately after the grown function bodies.
    import struct
    handlers=reader.function_id_to_exc_handlers.get(35728,[])
    if handlers:
        eh=struct.pack('<I',len(handlers))+b''.join(struct.pack('<III',locations[h.start],locations[h.end],locations[h.target]) for h in handlers)
        result,extra=insert_bodies(reader,result,[eh]);eh_offset=extra[0][0]
    small=reader.get_small_func_header_reader()
    for fid,loc in zip(raw,placed):
        m=small.from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(small));assert not m.overflowed
        m.offset,m.bytecodeSizeInBytes=loc;m.frameSize=max(m.frameSize,80)
        if fid in bodies:
            m.highestReadCacheIndex=bodies[fid].cache;m.highestWriteCacheIndex=len(bodies[fid].writes)
        if fid in HELPERS:m.paramCount=2;m.environmentSize=2 if fid in (PROVIDER_EFFECT,CHAT_EFFECT) else 0;m.hasExceptionHandler=0
        if fid==21967:m.environmentSize=19;m.highestReadCacheIndex=provider_cache
        if fid==35695:m.environmentSize=38;m.highestReadCacheIndex=f.cache
        if fid==35743:m.highestReadCacheIndex=send.cache
        if fid==35728:
            m.highestReadCacheIndex=clear.cache
            if handlers:m.infoOffset=eh_offset
        changed.add(fid);del m
    changes.append({'function':21967,'action':'verified journal notification selects actual project then Helper; scoped chat merge and viewability ack'})
    return result
