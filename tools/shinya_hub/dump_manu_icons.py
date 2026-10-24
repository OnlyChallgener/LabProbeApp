from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[22183]

insts = list(parse_hbc_bytecode(header, reader))

for i in insts:
    if 35113 in [getattr(i, 'arg1', None), getattr(i, 'arg2', None), getattr(i, 'arg3', None), getattr(i, 'arg4', None), getattr(i, 'string_id', None)]:
        print(f"Pos {hex(i.original_pos)}: {i}")
