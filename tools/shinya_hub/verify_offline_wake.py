"""Verify the patched original list uses the per-device eligibility field."""
import sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

r = HBCReader()
r.read_whole_file(open(sys.argv[1], 'rb'))
items = list(parse_hbc_bytecode(r.function_headers[22136], r))
matches = [i for i, op in enumerate(items) if op.inst.name == 'GetById'
           and op.arg1 == 24 and op.arg2 == 13 and r.strings[op.arg4] == 'remotectrl']
assert len(matches) == 1
i = matches[0]
assert items[i-1].inst.name == 'LoadFromEnvironment' and items[i-1].arg2 == 6 and items[i-1].arg3 == 0
assert items[i+1].inst.name == 'LoadConstFalse' and items[i+2].inst.name == 'JStrictNotEqualLong'
assert any(op.inst.name == 'JmpFalse' and getattr(op, 'arg2', None) == 17 for op in items[max(0,i-20):i])
print('offline wake list guard verified')
