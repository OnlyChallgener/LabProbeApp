from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())

for fid in [33444, 33446, 33447, 33448, 33449]:
    header = reader.function_headers[fid]
    print(f"\n================ FID {fid} ================")
    insts = list(parse_hbc_bytecode(header, reader))
    for i in insts:
        print(f"  {hex(i.original_pos)}: {i}")
