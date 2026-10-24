import json
from pathlib import Path

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
index = json.loads((ANALYSIS / 'index.json').read_text(encoding='utf-8'))

# Who calls functions in range 19850-19950?
all_firm_modules = set(range(19850, 19950))
callers = {}
for row in index:
    f_refs = set(row.get('f', [])) & all_firm_modules
    if f_refs:
        callers[row['id']] = (row.get('name'), f_refs)

for cid, (cname, refs) in sorted(callers.items()):
    if cid not in all_firm_modules:
        print(f"External Caller {cid} ({cname}) calls {len(refs)} firm modules: {list(refs)[:10]}")
