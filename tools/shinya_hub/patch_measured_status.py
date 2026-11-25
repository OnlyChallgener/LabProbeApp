"""Small null display guards for original diagnosis and signal coverage views.

Integration: data = additions(reader, data, changed_functions, changes).
Run after earlier body additions with a fresh reader; STRINGS is empty. No
helper IDs, request APIs, or original card styles change. A zero-size native
watcher refreshes only the original coverage query after settings change.
Four original bodies move inside the bytecode region; footer hashing remains
owned by the caller. The existing '--' placeholder uses SID 1313.
"""
import ctypes

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hbc_grow import insert_bodies
from patch_terminal_ipv6 import Code

STRINGS = {}
FUNCTIONS = (24164, 24165, 33059, 33242, 24179)


def plans(reader):
    result = {}
    for fid, sites in ((24165, ((0xcc, 0, 20), (0x26a, 19, 27), (0x2ab, 19, 31))),
                       (33059, ((0x531, 33, 40), (0x75f, 37, 32), (0x973, 34, 38)))):
        replacements = {}
        for pos, dst, old_default in sites:
            # Zero is a valid manually entered wall count, not missing data.
            code = Code(reader)
            skip=code.branch('JNotEqual',dst,25 if fid==24165 else 28)
            code.string(dst, '--');code.finish(skip)
            replacements[pos-3] = ('JmpTrue', [6,dst], bytes(code.raw))
            replacements[pos] = ('Mov', [dst, old_default], b'')
        result[fid] = ({}, replacements)
    # An absent cross-wall estimate must not activate the original achievement
    # badge merely because null != '??'. Keep its loading sentinel behavior.
    code = Code(reader)
    skip = code.branch('JNotEqual', 0, 25)
    code.string(0, '??'); code.finish(skip)
    result[24165][0][0x59] = bytes(code.raw)
    # Original DiagnoseDetail writes this value directly into Text.children.
    # null/undefined otherwise produces an empty score in the existing card.
    code = Code(reader)
    skip = code.branch('JNotEqual', 27, 28)
    code.string(27, '--'); code.finish(skip)
    result[33242] = ({0x43c: bytes(code.raw)}, {})
    # The original Tools card query has a one-hour staleTime. Retain its
    # React text, styling and topology; a zero-size native watcher invokes the
    # existing coverage-only refetch when settings change or the card returns.
    code=Code(reader)
    code.emit('StoreToEnvironment',10,8,0)
    code.emit('GetById',5,0,reader.function_headers[24164].highestReadCacheIndex+1,reader.strings.index('refetchCoverData'))
    code.emit('StoreToEnvironment',10,9,5)
    deps=Code(reader)
    for index,field in ((13,'projectId'),(14,'sn')):
        deps.emit('LoadFromEnvironment',0,10,8)
        deps.emit('GetById',0,0,reader.function_headers[24164].highestReadCacheIndex+(index-11),reader.strings.index(field))
        deps.emit('PutOwnByIndex',1,0,index)
    deps.emit('LoadFromEnvironment',0,10,9);deps.emit('PutOwnByIndex',1,0,15)
    result[24164]=({0x60:bytes(code.raw),0x14c:bytes(deps.raw)}, {0x90:('NewArray',[1,13],bytes([ops.NewArray.opcode,1,16,0]))})
    code=Code(reader);overview_cache=reader.function_headers[24165].highestReadCacheIndex
    def overview_get(dst,obj,name):
        nonlocal overview_cache
        overview_cache+=1;code.emit('GetById',dst,obj,overview_cache,reader.strings.index(name))
    code.emit('GetGlobalObject',19);code.string(20,'Hub');code.string(21,'coverage');code.emit('Add',20,20,21)
    code.emit('GetByVal',22,19,20);exists=code.branch('JmpTrueLong',22)
    code.emit('GetEnvironment',23,1);code.emit('LoadFromEnvironment',23,23,3);overview_get(24,23,'requireNativeComponent')
    code.emit('Call2',22,24,23,20);code.emit('PutByVal',19,20,22);code.finish(exists)
    code.emit('NewObject',23);code.emit('GetEnvironment',24,0);code.emit('LoadFromEnvironment',25,24,8)
    missing=code.branch('JmpFalseLong',25)
    for name in ('projectId','sn'):
        overview_get(26,25,name);code.put(23,26,name)
    code.finish(missing)
    code.emit('LoadFromEnvironment',26,24,9);code.put(23,26,'onChange')
    code.string(26,'watch');code.put(23,26,'type')
    code.emit('NewObject',25);code.number(26,0)
    code.put(25,26,'width');code.put(25,26,'height');code.put(23,25,'style')
    code.emit('Call3',22,3,4,22,23);code.emit('PutOwnByIndex',0,22,2)
    result[24165][0][0x443]=bytes(code.raw)
    # Export the selector's actual project and main AP identities. coverData
    # is a measurement object, not a router identity, and need not contain SN.
    # Original hook uses r0..r17. The expanded frame keeps these retained
    # identities outside both original temporaries and outgoing call staging.
    for ins in parse_hbc_bytecode(reader.function_headers[24179],reader):
        for index,operand in enumerate(ins.inst.operands,1):
            if operand.operand_type.name.startswith('Reg'):
                assert getattr(ins,'arg'+str(index))<18,('coverage identity register overlaps original hook',ins)
    capture=Code(reader);capture.emit('Mov',18,2);capture.emit('Mov',19,12)
    export=Code(reader)
    for offset,(obj,field) in enumerate(((18,'projectId'),(19,'sn')),1):
        export.emit('LoadConstUndefined',20)
        absent=export.branch('JmpFalseLong',obj)
        export.emit('GetById',20,obj,reader.function_headers[24179].highestReadCacheIndex+offset,reader.strings.index(field));export.finish(absent)
        export.put(0,20,field)
    result[24179]=({0x39:bytes(capture.raw),0x319:bytes(export.raw)}, {})
    # Keep the original card and topology. Replace only its three-value row
    # with the native, synchronously restored manual-metadata component.
    code=Code(reader);code.emit('StoreToEnvironment',18,17,25)
    result[33059][0][0x2a]=bytes(code.raw)
    code=Code(reader)
    cache=reader.function_headers[33059].highestReadCacheIndex
    def get(dst,obj,name):
        nonlocal cache
        cache+=1;code.emit('GetById',dst,obj,cache,reader.strings.index(name))
    code.emit('GetGlobalObject',37);code.string(38,'Hub');code.string(40,'coverage');code.emit('Add',38,38,40)
    code.emit('GetByVal',34,37,38);exists=code.branch('JmpTrueLong',34)
    code.emit('LoadFromEnvironment',36,21,5);get(33,36,'requireNativeComponent')
    code.emit('Call2',34,33,36,38);code.emit('PutByVal',37,38,34);code.finish(exists)
    code.emit('NewObject',35);code.emit('LoadFromEnvironment',36,18,17)
    missing=code.branch('JmpFalseLong',36)
    for name in ('projectId','sn'):
        get(40,36,name);code.put(35,40,name)
    code.finish(missing)
    code.emit('NewObject',39);code.number(40,80);code.put(39,40,'height')
    code.string(40,'stretch');code.put(39,40,'alignSelf');code.put(35,39,'style')
    code.emit('LoadFromEnvironment',33,21,0);code.emit('LoadFromEnvironment',36,21,1)
    code.number(40,18);code.emit('GetByVal',36,36,40);code.emit('Call2',36,33,3,36);get(33,36,'jsx')
    code.emit('Call3',7,33,3,34,35)
    result[33059][0][0xb2e]=bytes(code.raw)
    return result


def rewrite(reader, fid, insertions, replacements):
    h = reader.function_headers[fid]
    assert not h.hasExceptionHandler
    instructions = list(parse_hbc_bytecode(h, reader))
    by_pos = {i.original_pos: i for i in instructions}
    assert set(insertions) <= set(by_pos)
    for pos, (name, args, _) in replacements.items():
        ins = by_pos[pos]
        assert ins.inst.name == name
        assert [getattr(ins, 'arg'+str(n+1)) for n in range(len(ins.inst.operands))] == args
    sizes = {i.original_pos: 1 + len(bytes(i.inst.structure())) for i in instructions}
    promoted = set()
    while True:
        cursor = 0; location = {}
        for ins in instructions:
            pos = ins.original_pos
            location[pos] = cursor
            cursor += len(insertions.get(pos, b''))
            cursor += len(replacements[pos][2]) if pos in replacements else sizes[pos] + (3 if pos in promoted else 0)
        location[h.bytecodeSizeInBytes] = cursor
        grew = False
        for ins in instructions:
            if not ins.inst.operands or 'Addr' not in ins.inst.operands[0].operand_type.name:
                continue
            if ins.original_pos in replacements:continue
            delta = location[ins.original_pos + ins.arg1] - (location[ins.original_pos] + len(insertions.get(ins.original_pos,b'')))
            if ins.inst.operands[0].operand_type.name == 'Addr8' and not -128 <= delta <= 127 and ins.original_pos not in promoted:
                promoted.add(ins.original_pos); grew = True
        if not grew: break
    body = bytearray()
    for ins in instructions:
        pos = ins.original_pos
        body.extend(insertions.get(pos, b''))
        if pos in replacements:
            body.extend(replacements[pos][2]); continue
        name = ins.inst.name
        args = [getattr(ins, 'arg'+str(n+1)) for n in range(len(ins.inst.operands))]
        if ins.inst.operands and 'Addr' in ins.inst.operands[0].operand_type.name:
            args[0] = location[pos + ins.arg1] - len(body)
        if pos in promoted: name += 'Long'
        op = getattr(ops, name); operand = op.structure()
        for (field, _), value in zip(operand._fields_, args): setattr(operand, field, value)
        body.append(op.opcode); body.extend(bytes(operand))
    return bytes(body), location, len(promoted)


def additions(reader, data, changed, changes):
    assert reader.header.version == 90 and reader.strings[1313] == '--'
    expected = {24164:1099, 24165: 1141, 33059: 4920, 33242: 1406, 24179:795}
    bodies = []
    for fid, (insertions, replacements) in plans(reader).items():
        assert reader.function_headers[fid].bytecodeSizeInBytes == expected[fid], fid
        body, _, promoted = rewrite(reader, fid, insertions, replacements)
        bodies.append((fid, body, promoted))
    result, placed = insert_bodies(reader, data, [body for _, body, _ in bodies])
    locations = [(fid, offset, size, promoted)
                 for (fid, _, promoted), (offset, size) in zip(bodies, placed)]
    small = reader.get_small_func_header_reader()
    for fid, offset, size, promoted in locations:
        meta = small.from_buffer(result, reader.functions_offset + fid * ctypes.sizeof(small))
        if meta.overflowed:
            meta = reader.get_large_func_header_reader().from_buffer(result, (meta.infoOffset << 16) | meta.offset)
        meta.offset = offset; meta.bytecodeSizeInBytes = size
        if fid==24164:
            meta.environmentSize=max(reader.function_headers[fid].environmentSize,10)
            meta.highestReadCacheIndex=reader.function_headers[fid].highestReadCacheIndex+3
        if fid==24165:
            meta.highestReadCacheIndex=reader.function_headers[fid].highestReadCacheIndex+3
        if fid==33059:
            meta.environmentSize=18
            meta.highestReadCacheIndex=reader.function_headers[fid].highestReadCacheIndex+5
        if fid==24179:
            meta.frameSize=max(meta.frameSize,32)
            meta.highestReadCacheIndex=reader.function_headers[fid].highestReadCacheIndex+2
        # Placeholder replacements use existing registers only. Preserve the
        # frame-relative staging of the original six Construct instructions.
        changed.add(fid)
        changes.append({'function':fid, 'action':'show existing placeholder for unavailable measured values',
                        'appendedBytes':size, 'promotedBranches':promoted})
        del meta
    return result

