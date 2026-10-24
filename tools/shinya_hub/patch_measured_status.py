"""Small null display guards for original diagnosis and signal coverage views.

Integration: data = additions(reader, data, changed_functions, changes).
Run after earlier body additions with a fresh reader; STRINGS is empty. No
helper IDs, APIs, React elements, styles, environments, or list builders change.
Only these three original bodies move before the footer; footer hashing remains
owned by the caller. The existing '--' placeholder uses SID 1313.
"""
import ctypes

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops
from hbc_grow import insert_bodies
from patch_terminal_ipv6 import Code

STRINGS = {}
FUNCTIONS = (24165, 33059, 33242)


def plans(reader):
    result = {}
    for fid, sites in ((24165, ((0xcc, 0, 20), (0x26a, 19, 27), (0x2ab, 19, 31))),
                       (33059, ((0x531, 33, 40), (0x75f, 37, 32), (0x973, 34, 38)))):
        replacements = {}
        for pos, dst, old_default in sites:
            code = Code(reader); code.string(dst, '--')
            replacements[pos] = ('Mov', [dst, old_default], bytes(code.raw))
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
            assert ins.original_pos not in replacements
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
    expected = {24165: 1141, 33059: 4920, 33242: 1406}
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
        if fid == 33059: meta.frameSize = max(meta.frameSize, 60)
        changed.add(fid)
        changes.append({'function':fid, 'action':'show existing placeholder for unavailable measured values',
                        'appendedBytes':size, 'promotedBranches':promoted})
        del meta
    return result

