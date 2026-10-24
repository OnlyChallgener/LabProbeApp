import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

icon_firm_funcs = []
for row in index:
    name = row.get('name', '')
    if 'Firm' in name or 'Brand' in name or 'firm' in name:
        icon_firm_funcs.append((row['id'], name, row.get('s', [])))

print(f"Total firm functions: {len(icon_firm_funcs)}")
for fid, name, sids in icon_firm_funcs:
    s_texts = [reader.strings[s] for s in sids if len(reader.strings[s]) < 30]
    print(f"FID {fid}: {name} -> strings: {s_texts}")
