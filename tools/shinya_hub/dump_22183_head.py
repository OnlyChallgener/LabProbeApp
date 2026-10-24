from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[22183]

insts = list(parse_hbc_bytecode(header, reader))

# Look at instructions around 0x0 to 0x8b9
for i in insts:
    pos = i.original_pos
    if 0x0 <= pos <= 0x100:
        print(f"  {hex(pos)}: {i}")
