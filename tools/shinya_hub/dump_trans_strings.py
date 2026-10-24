from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from pathlib import Path

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())

for fid in range(7933, 7940):
    header = reader.function_headers[fid]
    sids = []
    for i in parse_hbc_bytecode(header, reader):
        for attr in ['arg1', 'arg2', 'arg3', 'arg4', 'string_id']:
            val = getattr(i, attr, None)
            if isinstance(val, int) and val < len(reader.strings):
                s = reader.strings[val]
                if s and len(s) < 40 and s not in sids:
                    sids.append(s)
    print(f"FID {fid}: {sids[:10]}")
