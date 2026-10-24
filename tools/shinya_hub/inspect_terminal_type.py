import json
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

keywords = ['设备类型', '品牌', 'NAS', 'nas', '电脑', '电视', '平板', '手机', '音箱', '摄像头', '打印机', '插座', '扫地机', '游戏机']
matching_sids = {}
for sid, s in enumerate(reader.strings):
    for kw in keywords:
        if kw in s and len(s) < 100:
            matching_sids[sid] = s
            break

print(f"Found {len(matching_sids)} matching strings")
for sid, s in sorted(matching_sids.items())[:50]:
    print(f"SID {sid}: {repr(s)}")

# Now find functions that reference these SIDs
sid_to_funcs = {}
for row in index:
    f_sids = set(row.get('s', [])) & set(matching_sids.keys())
    if f_sids:
        sid_to_funcs[row['id']] = (row.get('name'), f_sids)

print(f"\nFunctions referencing these keywords: {len(sid_to_funcs)}")
for fid, (fname, fs) in sorted(sid_to_funcs.items())[:30]:
    print(f"FID {fid} ({fname}): {[matching_sids[s] for s in fs]}")
