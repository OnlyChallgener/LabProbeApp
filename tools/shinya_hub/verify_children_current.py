"""Capture project-scoped children's API shapes and errors without credentials."""
import json,time,warnings
from pathlib import Path
from urllib3.exceptions import InsecureRequestWarning
from local_api import account,BASE,post
proof={'observedAt':time.time(),'projects':[]}
with account() as session:
 for project in post(session,'apMonitor/getProjectList2',{})['dataList']:
  pid=project['projectId'];result={'projectId':pid,'apis':[]}
  for route in ('netguard/staGuard/getProtectStaListPage','netguard/staGuard/getProtectMenus'):
   for explicit in (False,True):
    body={'projectId':pid}
    if explicit:body['apSn']=project['apList'][0]['sn']
    with warnings.catch_warnings():
     warnings.simplefilter('ignore',InsecureRequestWarning)
     value=session.post(BASE+route,json=body,timeout=20).json()
    data=value.get('data');row={'route':route,'explicitSn':explicit,'code':value.get('code'),'message':value.get('msg'),'dataType':type(data).__name__,'data':data}
    result['apis'].append(row)
    print(json.dumps({k:v for k,v in row.items() if k!='data'},ensure_ascii=False),flush=True)
  proof['projects'].append(result)
Path('test/_analysis/oct10-pages/children-current.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
