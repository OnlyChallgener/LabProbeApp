"""Execute the actual Hermes detail predicate against both MAC contracts."""
import argparse
import hashlib
import json
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from patch_bundle import load
from patch_station_lookup import additions, MATCHER, HELPER


def check_frame(reader, fid):
    """Validate real header capacity, including the implicit outgoing frame."""
    header = reader.function_headers[fid]
    instructions = list(parse_hbc_bytecode(header, reader))
    registers = [getattr(ins, 'arg' + str(n + 1))
        for ins in instructions for n, operand in enumerate(ins.inst.operands)
        if operand.operand_type.name in ('Reg8', 'Reg32')]
    locals_count = max(registers, default=-1) + 1
    outgoing = max((int(ins.inst.name[4:]) for ins in instructions
                    if ins.inst.name in ('Call1', 'Call2', 'Call3', 'Call4')), default=0)
    implicit=any(ins.inst.name in ('Call','CallLong','Construct','ConstructLong') for ins in instructions)
    # Mov destinations used for implicit outgoing arguments already include
    # the frame's reserved call area. Counting them again as locals then
    # adding another header inflated valid frames and broke Construct.
    required = locals_count if implicit else locals_count + (6 + outgoing if outgoing else 0)
    assert header.frameSize >= required, (fid, 'frame too small', header.frameSize, required)
    return {'frameSize': header.frameSize, 'required': required}


def execute(reader, bodies, fid, row, route, calls=None):
    calls = [] if calls is None else calls
    code = bodies.get(fid)
    header = reader.function_headers[fid]
    if code is None:
        instructions = list(parse_hbc_bytecode(header, reader))
    else:
        from io import BytesIO
        old_buffer = reader.file_buffer
        import copy
        h = copy.copy(header)
        h.offset = 0
        h.bytecodeSizeInBytes = len(code)
        reader.file_buffer = BytesIO(code)
        try:
            instructions = list(parse_hbc_bytecode(h, reader))
        finally:
            reader.file_buffer = old_buffer
    positions = {i.original_pos: n for n, i in enumerate(instructions)}
    regs = {}
    pc = 0

    def get(owner, key):
        if isinstance(owner, dict):
            return owner.get(key)
        if key == 'split':
            return lambda separator: owner.split(separator)
        if key == 'join':
            return lambda separator: separator.join(owner)
        if key == 'toUpperCase':
            return lambda: owner.upper()
        raise AssertionError((owner, key))

    for _ in range(300):
        i = instructions[pc]
        name = i.inst.name
        args = [getattr(i, 'arg'+str(k)) for k in range(1, 7) if hasattr(i, 'arg'+str(k))]
        pc += 1
        if name == 'LoadParam': regs[args[0]] = row
        elif name in ('GetById', 'GetByIdShort'): regs[args[0]] = get(regs[args[1]], reader.strings[args[3]])
        elif name == 'GetEnvironment': regs[args[0]] = {1: route}
        elif name == 'LoadFromEnvironment': regs[args[0]] = regs[args[1]][args[2]]
        elif name in ('LoadConstNull', 'LoadConstUndefined'): regs[args[0]] = None
        elif name == 'LoadConstString': regs[args[0]] = reader.strings[args[1]]
        elif name == 'LoadConstFalse': regs[args[0]] = False
        elif name == 'TypeOf': regs[args[0]] = 'string' if isinstance(regs[args[1]], str) else 'object'
        elif name == 'StrictEq': regs[args[0]] = type(regs[args[1]]) is type(regs[args[2]]) and regs[args[1]] == regs[args[2]]
        elif name == 'CreateClosure':
            child = args[2]
            regs[args[0]] = lambda value, child=child: execute(reader, bodies, child, value, route, calls)
        elif name == 'Call2': regs[args[0]] = regs[args[1]](regs[args[3]])
        elif name == 'Call1': regs[args[0]] = regs[args[1]]()
        elif name == 'JEqual':
            if regs[args[1]] == regs[args[2]]: pc = positions[i.original_pos+args[0]]
        elif name == 'JmpFalseLong':
            if not regs[args[1]]: pc = positions[i.original_pos+args[0]]
        elif name == 'JStrictNotEqualLong':
            if type(regs[args[1]]) is not type(regs[args[2]]) or regs[args[1]] != regs[args[2]]:
                pc = positions[i.original_pos+args[0]]
        elif name == 'Ret': return regs[args[0]]
        else: raise AssertionError(name)
    raise AssertionError('predicate did not return')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[2] / 'test/_analysis/oct08-shinya-hub/index.android.bundle'
    original = source.read_bytes()
    reader = load(original)
    assert not execute(reader, {}, MATCHER, {'mac': 'DA1F850C19FC'}, {'mac': 'da:1f:85:0c:19:fc'})
    data = bytearray(original)
    def replace(fid, code, reason):
        header = reader.function_headers[fid]
        assert len(code) <= header.bytecodeSizeInBytes, (fid, len(code))
        data[header.offset:header.offset+header.bytecodeSizeInBytes] = code + bytes(header.bytecodeSizeInBytes-len(code))
    additions(reader, original, replace, data)
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    reader = load(bytes(data))
    bodies = {}
    # Regression: the old leaf frame (5) must fail even though all explicit
    # registers r0..r3 fit and interpreted comparisons would pass.
    matcher_header = reader.function_headers[MATCHER]
    actual_frame = matcher_header.frameSize
    matcher_header.frameSize = 5
    try:
        check_frame(reader, MATCHER)
    except AssertionError:
        pass
    else:
        raise AssertionError('unsafe original leaf frame was not rejected')
    finally:
        matcher_header.frameSize = actual_frame
    if args.bundle:
        reader = load(args.bundle.read_bytes())
        bodies = {}
    frames = {str(fid): check_frame(reader, fid) for fid in (MATCHER, HELPER)}
    cases = [
        ('DA1F850C19FC', 'da:1f:85:0c:19:fc', True),
        ('DA1F850C19FC', 'DA-1F-85-0C-19-FC', True),
        ('DA1F850C19FC', 'DA1F850C19FC', True),
        ('da:1f:85:0c:19:fc', 'DA1F850C19FC', True),
        ('DA1F850C19FC', 'DA1F850C19FD', False),
        ('DA1F850C19FC', '', False),
        ('', ':-', False),
        (None, 'DA1F850C19FC', False),
        ('DA1F850C19FC', None, False),
        ('DA1F850C19FC', 1, False),
    ]
    for actual, routed, expected in cases:
        row = {'mac': actual, 'remark': 'iQOO Neo3', 'type': 'phone', 'manufacturer': 'vivo'}
        route = {'mac': routed}
        assert execute(reader, bodies, MATCHER, row, route) is expected, (actual, routed)
        assert row == {'mac': actual, 'remark': 'iQOO Neo3', 'type': 'phone', 'manufacturer': 'vivo'}
        assert route == {'mac': routed}
    assert execute(reader, bodies, MATCHER, {'mac': 'DA1F850C19FC'}, None) is False
    print(json.dumps({'passed': len(cases)+2, 'baselineMismatchReproduced': True,
        'actualBundle': str(args.bundle) if args.bundle else None,
        'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'frames': frames, 'unsafeLeafFrameRejected': True,
        'scope': 'Actual Hermes predicate instructions and header frame capacity; UI capture remains separate.'}))


if __name__ == '__main__': main()
