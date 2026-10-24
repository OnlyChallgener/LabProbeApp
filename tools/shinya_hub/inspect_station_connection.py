"""Compare local real-firmware projection with original UI field consumers."""
import json,sys
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.store import ProjectStore
from shinya_hub.station_identity import choices
from shinya_hub.projection import station_snapshots
from patch_bundle import load
root=Path('test/_analysis/oct10-local-validation')
store=ProjectStore(root/'shinya.sqlite')
project={'projectId':'hub:G1TD8RX039025','apList':[{'sn':'G1TD8RX039025','online':True}]}
rows=station_snapshots(store,'local-qa',project)
print(json.dumps({'choices':choices(store,'local-qa',project['projectId']),
 'wiredRows':[{k:r.get(k) for k in ('mac','hostname','type','typeLevel2','manufacturer','connectType','accessType','wifiBand','ifName','wlanId','online')} for r in rows if r.get('hostname') in ('fnos','DESKTOP-0IQS70U')]},ensure_ascii=False))
if '--projection-only' in sys.argv:sys.exit(0)
analysis=Path('test/_analysis/oct08-shinya-hub')
reader=load((analysis/'index.android.bundle').read_bytes())
index=json.loads((analysis/'index.json').read_text(encoding='utf-8'))
wanted={i for i,s in enumerate(reader.strings) if s in ('connectType','accessType','wire','wired','ifName','wifiBand','有线上网','有线接入')}
for row in index:
 hits=set(row.get('s',[])) & wanted
 if hits:print(json.dumps({'id':row['id'],'name':row.get('name'),'strings':[reader.strings[i] for i in sorted(hits)]},ensure_ascii=False))
