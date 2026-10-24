"""Keep terminal reports fresh on entry and while the detail is visible."""
import ctypes
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hbc_grow import insert_bodies


def additions(reader, data, changed, changes):
    fid=33301
    h=reader.function_headers[fid]
    assert not h.hasExceptionHandler
    source=bytes(data[h.offset:h.offset+h.bytecodeSizeInBytes])
    instructions=list(parse_hbc_bytecode(h,reader))
    # Each options object already contains its scoped enabled/select values.
    # Refreshing never bypasses these guards or changes the official chart UI.
    insert_at={0xa78,0xb01,0xb8a}
    from patch_terminal_ipv6 import Code
    extra=Code(reader)
    assert h.frameSize==103
    extra.string(93,'always')
    extra.put(2,93,'refetchOnMount')
    extra.number(93,30000)
    extra.put(2,93,'refetchInterval')
    extra.number(93,0)
    extra.put(2,93,'staleTime')
    for pos in insert_at:
        ins=next(i for i in instructions if i.original_pos==pos)
        assert ins.inst.name=='CreateClosure'
    sizes={i.original_pos:len(bytes(i.inst.structure()))+1 for i in instructions}
    long=set()
    while True:
        location={};cursor=0
        for i in instructions:
            if i.original_pos in insert_at:cursor+=len(extra.raw)
            location[i.original_pos]=cursor
            cursor+=sizes[i.original_pos]+(3 if i.original_pos in long else 0)
        location[len(source)]=cursor
        grew=False
        for i in instructions:
            if not i.inst.operands or 'Addr' not in i.inst.operands[0].operand_type.name:continue
            delta=location[i.original_pos+i.arg1]-location[i.original_pos]
            if i.inst.operands[0].operand_type.name=='Addr8' and not -128<=delta<128 and i.original_pos not in long:
                long.add(i.original_pos);grew=True
        if not grew:break
    body=bytearray()
    for i in instructions:
        if i.original_pos in insert_at:body.extend(extra.raw)
        name=i.inst.name
        args=[getattr(i,'arg'+str(n+1)) for n in range(len(i.inst.operands))]
        if i.inst.operands and 'Addr' in i.inst.operands[0].operand_type.name:
            args[0]=location[i.original_pos+i.arg1]-location[i.original_pos]
        if i.original_pos in long:name+='Long'
        op=getattr(ops,name);operand=op.structure()
        for (field,_),value in zip(operand._fields_,args):setattr(operand,field,value)
        body.append(op.opcode);body.extend(bytes(operand))
    # The expanded body must stay inside the mapped bytecode region.
    result,[(offset,_)]=insert_bodies(reader,data,[body])
    small=reader.get_small_func_header_reader()
    meta=small.from_buffer(result,reader.functions_offset+fid*ctypes.sizeof(small))
    if meta.overflowed:
        meta=reader.get_large_func_header_reader().from_buffer(result,(meta.infoOffset<<16)|meta.offset)
    meta.offset=offset;meta.bytecodeSizeInBytes=len(body)
    meta.frameSize=104
    changed.add(fid)
    changes.append({'function':fid,'action':'refetch scoped duration on mount and every 30 seconds',
                    'appendedBytes':len(body),'promotedBranches':len(long)})
    return result
