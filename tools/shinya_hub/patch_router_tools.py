"""Append two line-icon entries to the inspected original router tools grid."""
import struct,ctypes
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
STRINGS={15621:'DDNS',12868:'防火墙',12873:'routerTools',
         9309:'asset:///hub-ui/ddns.png',9396:'asset:///hub-ui/firewall.png',
         20560:'内网穿透',20566:'asset:///hub-ui/intranet-penetration.png',
         20113:'capabilities',22470:'firewall',30565:'端口映射',33491:'asset:///hub-ui/port-mapping.png'}

def additions(reader,original,replace,data):
    # These slots belong exclusively to the removed WeChat integration.
    from pathlib import Path
    import json
    index=json.loads((Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/index.json').read_text(encoding='utf8'))
    by_id={f['id']:f for f in index};exclusive=set();todo=[20053,23454]
    while todo:
        fid=todo.pop()
        if fid in exclusive:continue
        exclusive.add(fid);todo.extend(by_id[fid]['f'])
    for sid in STRINGS:
        assert all(f['id'] in exclusive for f in index if sid in f['s']),sid

    c=bytearray([ops.GetEnvironment.opcode,1,1,ops.LoadFromEnvironment.opcode,5,1,0,
                 ops.LoadFromEnvironment.opcode,7,1,1,ops.LoadConstUndefined.opcode,4,
                 ops.GetEnvironment.opcode,2,0,ops.NewArray.opcode,0,8,0])
    for n,(kind,label,dependency) in enumerate([(23150,20086,26),(3990,19843,27),(17810,19968,28),
                                             (16278,19229,29),(370,19227,29),(15621,15621,None),(12868,12868,None),(30565,30565,None)]):
        if dependency is not None:
            c+=bytes([ops.LoadConstUInt8.opcode,6,dependency,ops.GetByVal.opcode,6,7,6,
                      ops.Call2.opcode,8,5,4,6])
        else:
            c+=bytes([ops.NewObject.opcode,8,ops.LoadConstString.opcode,9])+struct.pack('<H',9309 if n==5 else 9396 if n==6 else 33491)
            c+=bytes([ops.PutNewOwnById.opcode,8,9])+struct.pack('<H',reader.strings.index('uri'))
        c+=bytes([ops.NewObject.opcode,6,ops.LoadConstString.opcode,9])+struct.pack('<H',label)
        c+=bytes([ops.LoadConstString.opcode,10])+struct.pack('<H',kind)
        c+=bytes([ops.PutNewOwnByIdShort.opcode,6,10,208,ops.PutNewOwnByIdShort.opcode,6,9,101,
                  ops.PutNewOwnById.opcode,6,8])+struct.pack('<H',21932)
        if n==0:
            c+=bytes([ops.LoadFromEnvironment.opcode,9,2,6,ops.PutNewOwnById.opcode,6,9])+struct.pack('<H',43543)
        if n==2:
            c+=bytes([ops.LoadConstFalse.opcode,9,ops.LoadFromEnvironment.opcode,10,2,3,
                      ops.JmpFalse.opcode,0,10]);jump=len(c)-3
            c+=bytes([ops.GetById.opcode,10,10,1])+struct.pack('<H',46171)
            c+=bytes([ops.LoadConstString.opcode,9])+struct.pack('<H',1017)
            c+=bytes([ops.StrictEq.opcode,9,10,9]);c[jump+1]=len(c)-jump
            c+=bytes([ops.PutNewOwnById.opcode,6,9])+struct.pack('<H',24527)
        c+=bytes([ops.PutOwnByIndex.opcode,0,6,n])
    # Only a scoped, explicitly false capability hides the last (firewall)
    # entry. Unknown/loading data keeps the existing entry; no network or
    # native watcher is added to a render function. Dynamic keys are retired
    # strings, not assumed Hermes identifiers.
    from patch_terminal_ipv6 import Code
    gate=Code(reader)
    gate.emit('GetEnvironment',2,0);gate.emit('LoadFromEnvironment',6,2,3)
    missing=[gate.branch('JmpFalseLong',6)]
    for sid in (20113,12873):
        gate.emit('LoadConstString',7,sid);gate.emit('GetByVal',6,6,7)
        missing.append(gate.branch('JmpFalseLong',6))
    gate.emit('LoadConstString',7,22470);gate.emit('GetByVal',6,6,7)
    gate.emit('LoadConstFalse',7)
    missing.append(gate.branch('JStrictNotEqualLong',6,7))
    gate.emit('LoadConstUInt8',7,7);gate.emit('GetByVal',6,0,7)
    gate.emit('PutOwnByIndex',0,6,6)
    gate.string(6,'length');gate.emit('PutByVal',0,6,7)
    for jump in missing:gate.finish(jump)
    c+=gate.raw+bytes([ops.Ret.opcode,0]);router_body=bytes(c)
    replace(24024,bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]),'reserve router-tools header for grown capability-aware builder')

    # Relocate the expanded common-tools builder into exclusive retired
    # questionnaire storage, capturing the same lexical useMenus environment.
    h=reader.function_headers[24028]
    menu=bytearray(original[h.offset:h.offset+h.bytecodeSizeInBytes])
    assert menu[0x77:0x7b]==bytes([ops.NewArray.opcode,0,6,0])
    assert menu[-2:]==bytes([ops.Ret.opcode,0])
    menu[0x79]=7
    entry=bytearray([ops.NewObject.opcode,3,ops.LoadConstString.opcode,2])+struct.pack('<H',20560)
    entry+=bytes([ops.PutNewOwnByIdShort.opcode,3,2,101,ops.PutNewOwnByIdShort.opcode,3,2,208])
    # CommonTools renders item.icon directly; it does not use the router
    # card's IconFont branch. Supply the same packaged art as the page header.
    entry+=bytes([ops.NewObject.opcode,4,ops.LoadConstString.opcode,2])+struct.pack('<H',20566)
    entry+=bytes([ops.PutNewOwnById.opcode,4,2])+struct.pack('<H',reader.strings.index('uri'))
    entry+=bytes([ops.PutNewOwnById.opcode,3,4])+struct.pack('<H',21932)
    entry+=bytes([
                  ops.PutOwnByIndex.opcode,0,3,6])
    menu[-2:]=entry+bytes([ops.Ret.opcode,0])
    # ServiceWrapper still mounts GameFeedBack (23454). It must remain a null
    # component, never run a tools builder with a different lexical environment.
    # Place the private builder in its unreachable storage and relocate a
    # feature-exclusive erased child header to that body.
    helper=23455
    owners=[f['id'] for f in index if helper in f['f']]
    assert owners==[23454],owners
    replace(helper,bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]),'reserve erased feedback child for private tools builder')
    root=reader.function_headers[23454]
    offset=16
    storage=bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0])+bytes([ops.Unreachable.opcode]*(offset-4))+bytes(menu)
    small=reader.get_small_func_header_reader()
    raw=small.from_buffer(data,reader.functions_offset+helper*ctypes.sizeof(small))
    meta=reader.get_large_func_header_reader().from_buffer(data,(raw.infoOffset<<16)|raw.offset) if raw.overflowed else raw
    meta.offset=root.offset+offset;meta.bytecodeSizeInBytes=len(menu)
    source=reader.function_headers[24028]
    meta.frameSize=source.frameSize;meta.paramCount=source.paramCount
    meta.highestReadCacheIndex=source.highestReadCacheIndex;meta.highestWriteCacheIndex=source.highestWriteCacheIndex
    meta.hasExceptionHandler=0
    del meta
    raw_router=small.from_buffer(data,reader.functions_offset+24024*ctypes.sizeof(small))
    router_meta=reader.get_large_func_header_reader().from_buffer(data,(raw_router.infoOffset<<16)|raw_router.offset) if raw_router.overflowed else raw_router
    router_meta.bytecodeSizeInBytes=len(router_body)
    del router_meta
    # The public retired component executes only null/return. Its private
    # storage belongs to the two aliased builders, whose independent read
    # caches must not be rescanned as one giant (unreachable) function.
    root_raw=small.from_buffer(data,reader.functions_offset+23454*ctypes.sizeof(small))
    root_meta=reader.get_large_func_header_reader().from_buffer(data,(root_raw.infoOffset<<16)|root_raw.offset) if root_raw.overflowed else root_raw
    root_meta.bytecodeSizeInBytes=4
    del root_meta
    delegate=bytes([ops.GetEnvironment.opcode,0,0,ops.CreateClosure.opcode,1,0])+struct.pack('<H',helper)
    delegate+=bytes([ops.LoadConstUndefined.opcode,2,ops.Call1.opcode,0,1,2,ops.Ret.opcode,0])
    replace(24028,delegate,'delegate expanded common-tools builder into retired storage')

    # Replace only the retired new-menu analytics prefix. Every existing action
    # starts at its original offset, so its branch offsets remain identical.
    prefix=bytearray([ops.LoadParam.opcode,2,1,ops.LoadConstUndefined.opcode,0,
        ops.GetEnvironment.opcode,1,1,ops.GetByIdShort.opcode,3,2,1,208,
        ops.LoadConstString.opcode,4])+struct.pack('<H',15621)
    yes=len(prefix);prefix+=bytes([ops.JStrictEqual.opcode,0,4,3])
    prefix+=bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',20560)
    penetration_yes=len(prefix);prefix+=bytes([ops.JStrictEqual.opcode,0,4,3])
    prefix+=bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',30565)
    mapping_yes=len(prefix);prefix+=bytes([ops.JStrictEqual.opcode,0,4,3])
    prefix+=bytes([ops.LoadConstString.opcode,4])+struct.pack('<H',12868)
    no=len(prefix);prefix+=bytes([ops.JStrictNotEqual.opcode,0,4,3])
    target=len(prefix)
    prefix+=bytes([ops.LoadFromEnvironment.opcode,4,1,12,ops.GetById.opcode,4,4,1])+struct.pack('<H',22921)
    prefix+=bytes([ops.GetById.opcode,4,4,1])+struct.pack('<H',22266)
    # A retired literal is a String, not a Hermes Identifier. Dynamic access
    # avoids the original identifier-hash table returning an undefined method.
    prefix+=bytes([ops.LoadConstString.opcode,5])+struct.pack('<H',12873)
    prefix+=bytes([ops.GetByVal.opcode,5,4,5])
    prefix+=bytes([ops.GetEnvironment.opcode,1,0,ops.LoadFromEnvironment.opcode,2,1,3,
                  ops.GetByIdShort.opcode,2,2,1,210,ops.LoadFromEnvironment.opcode,4,1,2,
                  ops.GetByIdShort.opcode,4,4,1,198,ops.Call4.opcode,0,5,0,2,4,3,ops.Ret.opcode,0])
    tail_offset=max(0x5f,len(prefix))
    prefix[yes+1]=target-yes;prefix[penetration_yes+1]=target-penetration_yes;prefix[mapping_yes+1]=target-mapping_yes;prefix[no+1]=tail_offset-no
    # Padding is unreachable after the new handler's return; old actions jump
    # directly to 0x5f with their original item and parent environment intact.
    prefix+=bytes([ops.Unreachable.opcode]*(tail_offset-len(prefix)))
    h=reader.function_headers[23933]
    menu_handler=bytearray(bytes(prefix)+original[h.offset+0x5f:h.offset+h.bytecodeSizeInBytes])
    # patch_bundle's legacy fixed-offset edit still touches the old body; the
    # relocated function must carry the same Diagnose route itself.
    diagnose=0x1a8+(tail_offset-0x5f)
    assert menu_handler[diagnose]==ops.LoadConstString.opcode
    assert struct.unpack_from('<H',menu_handler,diagnose+2)[0]==473
    struct.pack_into('<H',menu_handler,diagnose+2,9780)
    menu_handler=bytes(menu_handler)
    replace(23933,bytes([ops.LoadConstNull.opcode,0,ops.Ret.opcode,0]),'reserve router tool handler for relocated body')
    assert len(storage)+len(menu_handler)<=root.bytecodeSizeInBytes,(len(storage),len(menu_handler),root.bytecodeSizeInBytes)
    replace(23454,storage+menu_handler,'keep feedback component null; store scoped router action handler')

    # ApCard renders its line icon by menu.type through IconFont; item.icon is
    # deliberately ignored. Reuse only two retired questionnaire/feedback
    # branches, preserving every other original glyph and branch address.
    h=reader.function_headers[17938];icon=bytearray(original[h.offset:h.offset+h.bytecodeSizeInBytes])
    instructions=list(parse_hbc_bytecode(h,reader))
    for n,i in enumerate(instructions):
        if i.inst.name!='LoadConstString' or reader.strings[i.arg2] not in ('feedback','satisfy_questionaire','account_share','internal_test'):continue
        sid={'feedback':15621,'satisfy_questionaire':12868,'account_share':20560,'internal_test':30565}[reader.strings[i.arg2]]
        struct.pack_into('<H',icon,i.original_pos+2,sid)
        jump=instructions[n+1];assert jump.inst.name=='JStrictEqualLong'
        start=jump.original_pos+jump.arg1
        stop=next(j.original_pos+2 for j in instructions if j.original_pos>=start and j.inst.name=='Ret')
        handler=bytes([ops.CreateClosure.opcode,1,0])+struct.pack('<H',20053)+bytes([
            ops.Call2.opcode,1,1,4,5,ops.Ret.opcode,1])
        icon[start:stop]=handler+bytes([ops.Unreachable.opcode]*(stop-start-len(handler)))
    replace(17938,bytes(icon),'line icons for new router entries through original IconFont layout')
    # Rendering helper occupies a removed WeChat factory, without borrowing
    # any live feature callback or changing the module/dependency tables.
    c=bytearray([ops.LoadParam.opcode,5,1,ops.GetByIdShort.opcode,2,5,1,163,
        ops.LoadConstString.opcode,1])+struct.pack('<H',15621)
    c+=bytes([ops.LoadConstString.opcode,6])+struct.pack('<H',9309)
    c+=bytes([ops.JStrictEqual.opcode,8,1,2,ops.LoadConstString.opcode,6])+struct.pack('<H',9396)
    c+=bytes([ops.LoadConstString.opcode,1])+struct.pack('<H',20560)
    c+=bytes([ops.JStrictNotEqual.opcode,8,1,2,ops.LoadConstString.opcode,6])+struct.pack('<H',20566)
    c+=bytes([ops.LoadConstString.opcode,1])+struct.pack('<H',30565)
    c+=bytes([ops.JStrictNotEqual.opcode,8,1,2,ops.LoadConstString.opcode,6])+struct.pack('<H',33491)
    c+=bytes([ops.GetGlobalObject.opcode,0,ops.GetById.opcode,1,0,1])+struct.pack('<H',31986)
    c+=bytes([ops.LoadConstUndefined.opcode,3,ops.LoadConstUInt8.opcode,2,18,ops.Call2.opcode,5,1,3,2,
        ops.GetByIdShort.opcode,5,5,1,19,ops.LoadConstUInt8.opcode,2,176,ops.Call2.opcode,1,1,3,2,
        ops.GetByIdShort.opcode,1,1,1,156,ops.NewObject.opcode,2,ops.NewObject.opcode,4,
        ops.PutNewOwnById.opcode,4,6])+struct.pack('<H',reader.strings.index('uri'))
    c+=bytes([ops.PutNewOwnByIdShort.opcode,2,4,225,ops.NewObject.opcode,4,ops.LoadConstUInt8.opcode,6,24,
        ops.PutNewOwnByIdShort.opcode,4,6,144,ops.PutNewOwnByIdShort.opcode,4,6,145,
        ops.PutNewOwnByIdShort.opcode,2,4,233,ops.Call3.opcode,0,1,3,5,2,ops.Ret.opcode,0])
    icon_body=bytes(c)
    icon_root=reader.function_headers[20053]
    assert len(icon_body)+len(router_body)<=icon_root.bytecodeSizeInBytes,(len(icon_body),len(router_body),icon_root.bytecodeSizeInBytes)
    replace(20053,icon_body+router_body,'render tool icons; store scoped router tools builder')
    raw_icon=small.from_buffer(data,reader.functions_offset+20053*ctypes.sizeof(small))
    icon_meta=reader.get_large_func_header_reader().from_buffer(data,(raw_icon.infoOffset<<16)|raw_icon.offset) if raw_icon.overflowed else raw_icon
    icon_meta.bytecodeSizeInBytes=len(icon_body)
    del icon_meta
    raw_handler=small.from_buffer(data,reader.functions_offset+23933*ctypes.sizeof(small))
    handler_meta=reader.get_large_func_header_reader().from_buffer(data,(raw_handler.infoOffset<<16)|raw_handler.offset) if raw_handler.overflowed else raw_handler
    handler_meta.offset=root.offset+len(storage);handler_meta.bytecodeSizeInBytes=len(menu_handler)
    del handler_meta
    raw_router=small.from_buffer(data,reader.functions_offset+24024*ctypes.sizeof(small))
    router_meta=reader.get_large_func_header_reader().from_buffer(data,(raw_router.infoOffset<<16)|raw_router.offset) if raw_router.overflowed else raw_router
    router_meta.offset=icon_root.offset+len(icon_body);router_meta.bytecodeSizeInBytes=len(router_body)
    del router_meta
