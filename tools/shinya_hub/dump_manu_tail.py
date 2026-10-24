from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[22183]

insts = list(parse_hbc_bytecode(header, reader))
for i in insts:
    if 0x490 <= i.original_pos <= 0x4be:
        print(f"  {hex(i.original_pos)}: {i}")
