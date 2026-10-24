import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

for row in index:
    if 18283 in row.get('s', []):
        print(f"Func {row['id']} ({row.get('name')}) uses 18283")

# Also search for '苹果', '华为', '小米' in strings and check what functions use them
brand_chinese = ['苹果', '华为', '小米', '三星', '荣耀', '联想', '戴尔', '华硕', '索尼', '微星']
for sid, s in enumerate(reader.strings):
    for b in brand_chinese:
        if s == b:
            print(f"Exact string SID {sid}: {repr(s)}")
