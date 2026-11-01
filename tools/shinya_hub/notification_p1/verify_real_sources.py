import sys,json
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/.tmp')
import zerotier_access as remote
script=r"""import sys,sqlite3,json,pathlib,tempfile,contextlib
sys.path.insert(0,'/opt/shinya-hub')
from shinya_hub.vendor.usage_aggregate import UsageAggregateStore
from shinya_hub.assistant_notifications import AssistantNotifications
from shinya_hub.daily import CHINA
from datetime import datetime,timedelta
class ReadOnlyUsage(UsageAggregateStore):
 def connect(self):
  db=sqlite3.connect('file:'+str(self.db_path)+'?mode=ro',uri=True);db.row_factory=sqlite3.Row;return db
class Store:
 def __init__(self,path):self.path=path
 def connect(self):
  db=sqlite3.connect(self.path);db.row_factory=sqlite3.Row;return db
 def atomic(self):return contextlib.nullcontext()
with tempfile.TemporaryDirectory(prefix='p1-real-source-') as temp:
 engine=AssistantNotifications(Store(temp+'/events.db'))
 summary={'reports':0,'apps':0,'ranges':0,'resolvedApps':0,'aggregateRanges':0,'explicitConfirmedSessions':0,'futureRanges':0,'types':{}}
 for p in pathlib.Path('/data/children').glob('*/usage.db'):
  db=sqlite3.connect('file:'+str(p)+'?mode=ro',uri=True)
  today=datetime.now(CHINA).date();cutoff=str(today-timedelta(days=2))
  groups=db.execute('SELECT DISTINCT router,mac,date FROM usage_app_minute WHERE date>=? AND date<=?',(cutoff,str(today))).fetchall();db.close()
  usage=ReadOnlyUsage(p.parent)
  for router,mac,day in groups:
   report=usage.report([mac],day,router=router);summary['reports']+=1
   for row in report.get('apps') or []:
    summary['apps']+=1;app=engine._app(row)
    if app:
     summary['resolvedApps']+=1;t=app.get('type','');summary['types'][t]=summary['types'].get(t,0)+1
    for s in row.get('sessionRanges') or []:
     summary['ranges']+=1
     if s.get('eventId') and s.get('endConfirmed'):summary['explicitConfirmedSessions']+=1
     else:summary['aggregateRanges']+=1
     if s.get('endEpoch',0)>datetime.now(CHINA).timestamp()+60:summary['futureRanges']+=1
 assert summary['reports']>0 and summary['ranges']>0,summary
 assert summary['futureRanges']==0,summary
 print(json.dumps(summary))
"""
c=remote.nas()
try:
 result=remote.run(c,'docker exec -i shinya-hub python -',stdin=script.encode(),sudo_password=remote.NAS_PASS,timeout=55)
 Path(__file__).with_suffix('.json').write_bytes(result);print(result.decode())
finally:c.close()
