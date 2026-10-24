import json
from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

for fid in [22182, 22183, 22184, 22161]:
    header = reader.function_headers[fid]
    row = index[fid]
    print(f"=== Function {fid} ({row.get('name')}) ===")
    print("Bytecode size:", header.bytecodeSizeInBytes)
    insts = list(parse_hbc_bytecode(header, reader))
    for i in insts[:60]:
        print(f"  {hex(i.original_pos)}: {i}")
    if len(insts) > 60:
        print(f"  ... ({len(insts)} total instructions)")
