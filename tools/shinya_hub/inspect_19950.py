import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

for row in index:
    if 19945 <= row['id'] <= 19970:
        s_texts = [reader.strings[s] for s in row.get('s', []) if len(reader.strings[s]) < 40]
        print(f"FID {row['id']} ({row.get('name')}): strings={s_texts}")
