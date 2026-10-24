from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
header = reader.function_headers[22183]

for i in parse_hbc_bytecode(header, reader):
    if i.inst.name == 'PutNewOwnById' and getattr(i, 'arg3', None) == 24987: # router
        print(f"Router put at {hex(i.original_pos)}: {i}")
    if i.inst.name == 'PutNewOwnById' and getattr(i, 'arg3', None) == 24643: # other
        print(f"Other put at {hex(i.original_pos)}: {i}")
