import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
for fid in range(len(reader.function_headers)):
    if fid == 0: continue
    header = reader.function_headers[fid]
    for inst in parse_hbc_bytecode(header, reader):
        if inst.inst.name in ('LoadConstInt', 'LoadConstUInt8') and getattr(inst, 'arg2', None) == 2027:
            print(f'FID {fid} loads 2027 at {inst.original_pos}')
