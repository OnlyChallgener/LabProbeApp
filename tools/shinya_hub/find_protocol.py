"""Find exact bundle functions that reference selected protocol strings."""
import argparse
import json
from pathlib import Path
from patch_bundle import load

root=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub'
parser=argparse.ArgumentParser()
parser.add_argument('terms',nargs='+')
args=parser.parse_args()
reader=load((root/'index.android.bundle').read_bytes())
index=json.loads((root/'index.json').read_text(encoding='utf-8'))
for row in index:
    found=[reader.strings[s] for s in row['s'] if any(t.lower() in reader.strings[s].lower() for t in args.terms)]
    if found:
        print(json.dumps({'id':row['id'],'name':row['name'],'strings':[s for s in found if len(s)<250]},ensure_ascii=False))
