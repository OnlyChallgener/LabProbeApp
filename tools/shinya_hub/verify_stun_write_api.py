"""Verify a disabled temporary rule, retaining every pre-existing STUN rule."""
import json
import os
from pathlib import Path

from remote_transport import connect_nas
from eweb_hub_remote import run

PROBE = r'''
import json,os,time,yaml,requests
from pathlib import Path
c=yaml.safe_load(Path(os.environ.get('CONFIG_PATH','/app/config/config.yaml')).read_text()) or {}
token=os.environ.get('APP_TOKEN') or c.get('server',{}).get('app_token')
if not token or token=='change-app-token':raise RuntimeError('Token unavailable')
s=requests.Session();s.trust_env=False;s.headers['Authorization']='Bearer '+token
base='http://127.0.0.1:58543/api/v1/'
def post(path,body):
    v=s.post(base+path,json=body,timeout=25).json()
    if v.get('code')!=0:raise RuntimeError('API rejected request: '+str(v.get('code')))
    return v['data']
p=next(p for p in post('apMonitor/getProjectList2',{})['dataList']
       if any(d['sn']=='G1TD8RX039025' for d in p.get('apList',[])))
scope={'projectId':p['projectId'],'apSn':'G1TD8RX039025'}
def op(action,**extra):return post('hub/stun',{**scope,'operation':action,**extra})
before=op('list')['rules']; ids={r['id'] for r in before}
name='Demon-validation-'+str(int(time.time()))
draft={'name':name,'serviceType':'custom','transportProtocol':'tcp','targetType':'manual',
       'targetIpv4':'192.168.5.46','targetPort':65090,'enabled':False}
created=''; result={'beforeCount':len(before),'create':False,'update':False,'delete':False}
try:
    op('create',draft=draft)
    rows=op('list')['rules']
    matches=[r for r in rows if r.get('name')==name and r['id'] not in ids]
    if len(matches)!=1:raise RuntimeError('Cannot identify temporary rule')
    created=matches[0]['id'];result['create']=matches[0].get('enabled') is False
    if not result['create']:raise RuntimeError('Temporary rule is unexpectedly enabled')
    op('update',ruleId=created,draft={**draft,'name':name+'-edited','targetPort':65091})
    r=next(r for r in op('list')['rules'] if r['id']==created)
    result['update']=r.get('name')==name+'-edited' and r.get('targetPort')==65091 and r.get('enabled') is False
    if not result['update']:raise RuntimeError('Updated rule did not round-trip')
finally:
    if created:
        op('delete',ruleId=created)
    else:
        # Creation may have succeeded even if its response was interrupted.
        for r in op('list')['rules']:
            if r['id'] not in ids and r.get('name') in {name,name+'-edited'}:op('delete',ruleId=r['id'])
    after=op('list')['rules']
    result['afterCount']=len(after)
    result['delete']={r['id'] for r in after}==ids
    if not result['delete']:raise RuntimeError('Original rule IDs changed')
print(json.dumps(result))
'''

if __name__ == '__main__':
    client = connect_nas()
    try:
        result = json.loads(run(client, 'docker exec -i labprobe-hub python -',
                                stdin=PROBE.encode(), sudo_password=os.environ['SHINYA_NAS_PASSWORD'],
                                timeout=58))
        path = Path(__file__).resolve().parents[2] / 'test/_analysis/oct09-implementation/deployed-tools-write.json'
        path.write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps(result))
    finally:
        client.close()
