from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[0]

for i in parse_hbc_bytecode(header, reader):
    if 0x4dd0 <= i.original_pos <= 0x4e20:
        print(f"  {hex(i.original_pos)}: {i}")
