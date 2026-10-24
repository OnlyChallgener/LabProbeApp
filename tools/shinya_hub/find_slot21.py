import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
for fid in range(21898, 21915):
    header = reader.function_headers[fid]
    for inst in parse_hbc_bytecode(header, reader):
        if inst.inst.name == 'LoadFromEnvironment' and inst.arg3 == 21:
            print(f'FID {fid} loads slot 21 at {inst.original_pos}')
