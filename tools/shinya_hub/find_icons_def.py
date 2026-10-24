import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

# Search for StaTypeIcons (sid 35106) and ManufacturerIcons (sid 35113)
for row in index:
    f_sids = set(row.get('s', []))
    if 35106 in f_sids or 35113 in f_sids:
        s_texts = [reader.strings[s] for s in f_sids if len(reader.strings[s]) < 40]
        print(f"FID {row['id']} ({row.get('name')}): child_funcs={row.get('f', [])} strings={s_texts[:20]}")
