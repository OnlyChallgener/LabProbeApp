import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
header = reader.function_headers[33301]
insts = list(parse_hbc_bytecode(header, reader))

for inst in insts:
    if inst.inst.name in ('LoadConstString', 'LoadConstStringLong'):
        s = reader.strings[inst.arg2]
        if any(ord(c) > 127 for c in s) or len(s) > 4:
            print(f'{inst.original_pos:5d}: "{s}"')
