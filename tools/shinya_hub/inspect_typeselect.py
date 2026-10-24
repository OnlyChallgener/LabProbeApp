import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

for row in index:
    if 33435 <= row['id'] <= 33475:
        s_texts = []
        for s in row.get('s', []):
            try:
                st = reader.strings[s]
                if len(st) < 40:
                    s_texts.append(f"{s}:{st}")
            except Exception:
                pass
        print(f"FID {row['id']} ({row.get('name')}): child_funcs={row.get('f', [])} strings={s_texts}")
