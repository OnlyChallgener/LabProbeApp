"""Locate original children/game card data contracts without changing layouts."""
import json
from pathlib import Path
from patch_bundle import load
p=Path('test/_analysis/oct08-shinya-hub');r=load((p/'index.android.bundle').read_bytes())
rows=json.loads((p/'index.json').read_text(encoding='utf-8'))
terms={'getProtectStaListPage','getProtectMenus','protectStaList','childHidden','netguardDisable','getGameStatistics','getAccelerateStatistics','getGameLog','assurance','getStaIdByStaMac'}
for row in rows:
 words=[r.strings[s] for s in row.get('s',[]) if len(r.strings[s])<100]
 if set(words)&terms and row['id']>19900 and len(words)<110:
  print(json.dumps({'id':row['id'],'name':row.get('name'),'strings':words,'functions':row.get('f',[])},ensure_ascii=False))
