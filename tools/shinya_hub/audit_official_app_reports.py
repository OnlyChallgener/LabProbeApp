"""Read official usage reports using each existing authorized account binding."""
import json
import os
import time
from remote_transport import connect_nas, run
from audit_application_usage import ROOT

PROBE = r'''
import json,os,sys
from pathlib import Path
sys.path.insert(0,'/app')
from official_child_guard import OfficialGuardClient
from assistant.security import decrypt_secret_with_migration
serial=__SERIAL__
paths=[Path(os.environ.get('DATA_DIR','/app/data'))/'official_child_guard_accounts.json',Path('/data/official_child_guard_accounts.json')]
source=next((p for p in paths if p.is_file()),None)
if source is None:raise RuntimeError('Existing authorization unavailable')
bindings=[json.loads(decrypt_secret_with_migration(v)[0]) for v in json.loads(source.read_text()).values()]
binding=next((v for v in bindings if v.get('serial')==serial),None)
if binding is None:raise RuntimeError('Existing authorization identity mismatch')
client=OfficialGuardClient(binding['token'],binding);client.session.trust_env=False
members=client.members()
result={'serial':serial,'members':len(members),'reports':[]}
for row in members:
 for day in ('2026-10-10','2026-10-09','2026-10-08'):
  params={'mainApSn':serial,'mainApCreateTime':binding['createTime'],
          'meshApSnList':','.join(binding['meshSerials']),'staId':row['staId'],'day':day}
  try:
   data=client.request('GET','/api/v1/netguard/onlineReport/getStaAppReport',params=params)
   result['reports'].append({'staId':row['staId'],'hostname':row.get('hostname'),'day':day,'data':data})
  except Exception as error:
   result['reports'].append({'staId':row['staId'],'day':day,'error':getattr(error,'code',type(error).__name__)})
print(json.dumps(result))
'''


def brief(value):
    if isinstance(value, list):
        return {'rows': len(value), 'firstShape': brief(value[0]) if value else None}
    if isinstance(value, dict):
        return {key: brief(item) for key, item in value.items()}
    return value if isinstance(value, (int, float, bool)) else type(value).__name__


if __name__ == '__main__':
    ROOT.mkdir(parents=True, exist_ok=True)
    client = connect_nas()
    try:
        for family, container, serial in [('be72','labprobe-hub','G1TD8RX039025'),
                                          ('be50','labprobe-hub-be50','G1TQ88T702111')]:
            probe = PROBE.replace('__SERIAL__', repr(serial))
            try:
                value = json.loads(run(client,'docker exec -i '+container+' python -',stdin=probe.encode(),
                                       sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=100))
                (ROOT / (family+'-official-reports.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
                print(json.dumps({'family':family,'members':value['members'],'reports':[
                    {'hostname':row.get('hostname'),'day':row['day'],'shape':brief(row.get('data')),
                     'error':row.get('error')} for row in value['reports']]}),flush=True)
            except Exception as error:
                print(json.dumps({'family':family,'error':type(error).__name__}),flush=True)
    finally:
        client.close()
