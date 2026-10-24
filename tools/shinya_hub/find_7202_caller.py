from pathlib import Path
from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())

# In Hermes, function 7202 has an environment or caller. Let's find callers of 7202.
for fid in range(len(reader.function_headers)):
    header = reader.function_headers[fid]
    for i in parse_hbc_bytecode(header, reader):
        if getattr(i, 'function_id', None) == 7202:
            print(f"Caller of 7202: fid {fid} at {hex(i.original_pos)}")
