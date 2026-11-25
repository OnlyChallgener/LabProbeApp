"""Execute the actual heading guard and compare the retained original card."""
import argparse
import hashlib
import math
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hbc_grow import assert_bodies_mapped
from patch_bundle import load, SOURCE_SHA
from patch_assistant_diagnosis_heading import FID, EXCEPTIONS, additions
from verify_terminal_ipv6_patch import check_read_caches
from verify_station_lookup import check_frame


def instructions(reader):
    return list(parse_hbc_bytecode(reader.function_headers[FID], reader))


def comparable(reader):
    result = []
    for ins in instructions(reader):
        args = [getattr(ins, 'arg' + str(n + 1)) for n in range(len(ins.inst.operands))]
        if len(args) == 4 and 'ById' in ins.inst.name:
            args[2] = 0  # Whole-bundle pass deduplicates cache slots by SID.
        result.append((ins.inst.name, args))
    return result


def evaluate(reader, count):
    code = instructions(reader)
    by_position = {i.original_pos: i for i in code}
    anchor = next(i for i in code if i.inst.name == 'GetById' and i.arg4 == EXCEPTIONS
                  and i.arg1 == 45 and i.arg2 == 44)
    start = code[code.index(anchor) - 1].original_pos
    assert by_position[start].inst.name == 'LoadFromEnvironment'
    registers = {12: {1: {'exceptionNumber': count}}}
    position = start
    for _ in range(12):
        ins = by_position[position]
        args = [getattr(ins, 'arg' + str(n + 1)) for n in range(len(ins.inst.operands))]
        name = ins.inst.name
        following = position + 1 + len(bytes(ins.inst.structure()))
        if name == 'LoadFromEnvironment':
            registers[args[0]] = registers[args[1]][args[2]]
        elif name == 'GetById':
            registers[args[0]] = registers[args[1]].get(reader.strings[args[3]])
        elif name == 'LoadConstZero':
            registers[args[0]] = 0
        elif name == 'LoadConstString':
            registers[args[0]] = reader.strings[args[1]]
        elif name in {'JNotGreater', 'JNotGreaterLong'}:
            try:
                value = float(registers[args[1]])
            except (ValueError, TypeError):
                value = math.nan
            if not value > registers[args[2]]:
                following = position + args[0]
        elif name == 'PutNewOwnByIdShort':
            assert reader.strings[args[2]] == 'children'
            return registers[27]
        else:
            raise AssertionError((position, name))
        position = following
    raise AssertionError('Heading guard did not terminate')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[2] / 'test/_analysis/oct08-shinya-hub/index.android.bundle'
    original_bytes = source.read_bytes()
    assert hashlib.sha256(original_bytes).hexdigest() == SOURCE_SHA
    original = load(original_bytes)
    actual = load(args.bundle.read_bytes())
    expected_bytes = additions(original, bytearray(original_bytes), set(), [], placeholder=18524)
    expected_bytes[-20:] = hashlib.sha1(expected_bytes[:-20]).digest()
    expected = load(bytes(expected_bytes))
    assert comparable(actual) == comparable(expected), 'Unrelated original diagnosis-card logic changed'
    check_frame(actual,FID)
    check_read_caches(actual, FID)
    assert_bodies_mapped(actual)
    for value in (0, '0', None, 'invalid', -1):
        assert evaluate(actual, value) == '诊断完成', value
    for value in (1, '2', 3.0):
        assert evaluate(actual, value) == '当前网络不佳', value
    print('PASS: actual heading bytecode for 8 missing/zero/positive cases; original diagnosis card retained except guarded headline, cache/frame/code mapping verified')


if __name__ == '__main__':
    main()
