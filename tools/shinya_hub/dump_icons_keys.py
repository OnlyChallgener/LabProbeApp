import json
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[22183]

insts = list(parse_hbc_bytecode(header, reader))

current_obj = None
for i in insts:
    name = i.inst.name
    if 'PutNewOwnById' in name or 'PutById' in name:
        # Check string argument
        for arg in ['arg2', 'arg3', 'arg4', 'string_id']:
            if hasattr(i, arg):
                val = getattr(i, arg)
                if isinstance(val, int) and val < len(reader.strings):
                    s = reader.strings[val]
                    print(f"  {hex(i.original_pos)}: {name} -> string: {val} ({s})")
