import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

for row in index:
    sids = row.get('s', [])
    for sid in sids:
        if sid < len(reader.strings):
            s = reader.strings[sid]
            if 'i18n' in s or 'zh_CN' in s or 'resources' in s:
                print(f"Func {row['id']} ({row.get('name')}) has string {repr(s)}")
                break
