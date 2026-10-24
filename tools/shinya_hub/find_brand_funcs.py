import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

sids = [19657, 20360, 21031, 21033]
for sid in sids:
    s_val = reader.strings[sid]
    funcs = [row['id'] for row in index if sid in row.get('s', [])]
    print(f"SID {sid}: {repr(s_val)} used in funcs {funcs}")
