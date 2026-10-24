import sys
sys.path.insert(0, 'tools/shinya_hub')
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

reader = load(Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes())
# In FID 0 or parent of 33301, find where [5, 11] is stored
# 33301 is inside what module?
for fid in range(len(reader.function_headers)):
    header = reader.function_headers[fid]
    for inst in parse_hbc_bytecode(header, reader):
        if inst.inst.name == 'CreateClosure' and hasattr(inst, 'arg3') and inst.arg3 == 33301:
            print(f'Parent of 33301 is FID {fid} at {inst.original_pos}')
