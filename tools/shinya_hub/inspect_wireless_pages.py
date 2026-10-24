"""List original wireless page request helpers and their consumers."""
import json
from pathlib import Path
from patch_bundle import load
p=Path('test/_analysis/oct08-shinya-hub')
r=load((p/'index.android.bundle').read_bytes())
rows=json.loads((p/'index.json').read_text(encoding='utf-8'))
for row in rows:
 words=[r.strings[s] for s in row.get('s',[]) if len(r.strings[s])<100]
 if any(any(t in x.lower() for t in ('healthy','timemanage','channel','energysaving')) for x in words) and row['id']>19900:
  print(json.dumps({'id':row['id'],'name':row.get('name'),'strings':words,'functions':row.get('f',[])},ensure_ascii=False))
