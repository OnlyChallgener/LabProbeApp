from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())

for fid in range(len(reader.function_headers)):
    header = reader.function_headers[fid]
    for i in parse_hbc_bytecode(header, reader):
        for attr in ['function_id', 'arg1', 'arg2', 'arg3', 'arg4']:
            if getattr(i, attr, None) == 7202:
                print(f"Func {fid} pos {hex(i.original_pos)}: {i.inst.name} {attr}=7202")
