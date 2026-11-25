"""Original station Popover row for explicit phone/pad/pc notification rules.

No new FID, no child guard mutation. Run after duration_refresh, with a fresh
reader. Merge STRINGS before allocation. Native rechecks device ownership.
"""
import ctypes
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hbc_grow import insert_bodies
from patch_measured_status import rewrite
from patch_terminal_ipv6 import Code

STRINGS={26676:'notifyRemind',37665:'通知提醒',46444:'stationNotify'}
FIDS=(33301,33364)


class C(Code):
    def __init__(self,reader,cache=0):
        super().__init__(reader);self.cache=cache

    def sid(self,text):
        for sid,value in STRINGS.items():
            if value==text:return sid
        return super().sid(text)

    def get(self,dst,obj,text):
        self.cache+=1
        self.emit('GetById',dst,obj,self.cache,self.sid(text))


def guard(c,row,route,base):
    """Fail closed on absent scope, mismatched source MAC or unknown subtype.

    The device list's broad type is authoritative. A specific subtype may be
    a brand/model, so it must not hide an otherwise eligible phone/tablet/PC.
    Explicit non-target subtypes still override a broad fallback (some NAS
    devices are represented as PCs by the firmware).
    """
    nil,kind,tmp,project,sn,mac=range(base,base+6)
    denied=[];c.emit('LoadConstNull',nil)
    denied.append(c.branch('JEqualLong',row,nil))
    denied.append(c.branch('JEqualLong',route,nil))
    c.get(kind,row,'typeLevel2')
    accepted=[]
    for text in ('phone','pad','pc'):
        c.string(tmp,text);accepted.append(c.branch('JStrictEqualLong',kind,tmp))
    for text in ('nas','camera','printer','tv','watch','router','appliances','sound','network','game','other'):
        c.string(tmp,text);denied.append(c.branch('JStrictEqualLong',kind,tmp))
    c.emit('TypeOf',project,kind);c.string(tmp,'string')
    fallback=c.branch('JStrictEqualLong',project,tmp)
    denied.append(c.branch('JStrictNotEqualLong',kind,nil))
    c.finish(fallback)
    c.get(kind,row,'type')
    for text in ('phone','pad','pc'):
        c.string(tmp,text);accepted.append(c.branch('JStrictEqualLong',kind,tmp))
    denied.append(c.branch('JmpLong'))
    for p in accepted:c.finish(p)
    for register,field in ((project,'projectId'),(sn,'sn'),(mac,'mac')):
        c.get(register,row,field)
        c.emit('TypeOf',kind,register);c.string(tmp,'string')
        denied.append(c.branch('JStrictNotEqualLong',kind,tmp))
        denied.append(c.branch('JmpFalseLong',register))
    for register,field in ((project,'projectId'),(sn,'sn'),(mac,'mac')):
        c.get(kind,route,field)
        denied.append(c.branch('JStrictNotEqualLong',kind,register))
    return denied,(project,sn,mac)


def menu_plan(reader):
    h=reader.function_headers[33301]
    code=list(parse_hbc_bytecode(h,reader))
    label=next(k for k,i in enumerate(code) if i.inst.name=='LoadConstString' and i.arg2==20780)
    start=next(k for k in range(label,-1,-1) if code[k].inst.name=='GetByIdShort'
               and (code[k].arg1,code[k].arg2,code[k].arg4)==(40,21,156))-2
    assert code[start].inst.name=='GetByVal' and (code[start].arg1,code[start].arg2,code[start].arg3)==(21,0,9)
    end=next(k for k in range(label,len(code)) if code[k].inst.name=='Call4'
             and (code[k].arg1,code[k].arg2,code[k].arg4,code[k].arg5)==(21,40,38,35))+1
    assert code[end].inst.name=='NewArray' and (code[end].arg1,code[end].arg2)==(40,1)
    assert code[end+1].inst.name=='PutOwnByIndex'
    insertion=code[end+2].original_pos
    c=C(reader,h.highestReadCacheIndex)
    c.emit('LoadFromEnvironment',100,8,2)
    c.emit('LoadFromEnvironment',101,8,1)
    denied,_=guard(c,100,101,102)
    c.emit('Mov',110,40)
    # Reuse the entire original Popover.Item/Flex/Icon/Text construction. No
    # reconstructed style or element hierarchy; only text and value differ.
    for i in code[start:end]:
        args=[getattr(i,'arg'+str(k+1)) for k in range(len(i.inst.operands))]
        if i.inst.name=='LoadConstString' and args[1] in (20780,32079):
            args[1]={20780:37665,32079:46444}[args[1]]
        c.emit(i.inst.name,*args)
    c.emit('PutOwnByIndex',110,21,1);c.emit('Mov',40,110)
    for p in denied:c.finish(p)
    return {insertion:bytes(c.raw)},c.cache


def selection(reader):
    c=C(reader)
    c.emit('LoadParam',1,1);c.string(2,'stationNotify')
    other=c.branch('JStrictNotEqualLong',1,2)
    c.emit('GetEnvironment',0,0)
    c.emit('LoadFromEnvironment',4,0,2)
    c.emit('LoadFromEnvironment',5,0,1)
    denied,scope=guard(c,4,5,6)
    c.emit('GetEnvironment',12,1)
    c.emit('LoadFromEnvironment',12,12,5) # station module RN dependency
    c.get(12,12,'NativeModules');c.get(12,12,'sta');c.get(13,12,'notifyRemind')
    c.emit('Call4',14,13,12,*scope)
    for p in denied:c.finish(p)
    c.emit('LoadConstUndefined',0);c.emit('Ret',0)
    c.finish(other)
    h=reader.function_headers[33364];reader.file_buffer.seek(h.offset)
    c.raw.extend(reader.file_buffer.read(h.bytecodeSizeInBytes))
    return bytes(c.raw),c.cache


def additions(reader,data,changed,changes):
    assert reader.header.version==90
    ins,cache=menu_plan(reader)
    menu,_,_=rewrite(reader,33301,ins,{})
    press,press_cache=selection(reader)
    result,placed=insert_bodies(reader,data,[menu,press])
    small=reader.get_small_func_header_reader()
    for fid,(offset,size),readcache,frame in zip(FIDS,placed,(cache,press_cache),(124,32)):
        meta=small.from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(small))
        if meta.overflowed:meta=reader.get_large_func_header_reader().from_buffer(result,(meta.infoOffset<<16)|meta.offset)
        assert not meta.hasExceptionHandler
        meta.offset=offset;meta.bytecodeSizeInBytes=size
        meta.highestReadCacheIndex=readcache;meta.frameSize=max(frame,meta.frameSize)
        changed.add(fid)
        changes.append({'function':fid,'action':'original scoped phone/pad/pc notification menu; no child guard write','appendedBytes':size})
        del meta
    return result
