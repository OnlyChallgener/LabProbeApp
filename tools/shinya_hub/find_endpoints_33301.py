import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
for fid in range(33301, 33378):
    header = reader.function_headers[fid]
    for inst in parse_hbc_bytecode(header, reader):
        if inst.inst.name in ('LoadConstString', 'LoadConstStringLong'):
            s = reader.strings[inst.arg2]
            if '/' in s or 'get' in s.lower() or 'post' in s.lower() or 'sta' in s.lower():
                print(f'FID {fid}: "{s}"')
