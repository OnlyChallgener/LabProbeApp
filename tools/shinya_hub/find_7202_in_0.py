from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[0]

for i in parse_hbc_bytecode(header, reader):
    if getattr(i, 'function_id', None) == 7202:
        print(f"FID 0 calls 7202 at {hex(i.original_pos)}: {i}")
