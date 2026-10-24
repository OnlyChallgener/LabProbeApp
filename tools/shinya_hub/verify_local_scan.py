"""Check fresh firmware surveys through the same scoped APIs as the local page."""
import json,time
from pathlib import Path
from local_api import account,post
OUT=Path('test/_analysis/oct12-local-scan');OUT.mkdir(exist_ok=True)
with account() as session:
    scopes=[{'projectId':p['projectId'],'sn':p['apList'][0]['sn']} for p in post(session,'apMonitor/getProjectList2',{})['dataList']]
    for scope in scopes:post(session,'device/state/asyncChannelScan',scope)
    until=time.monotonic()+100;results={}
    while len(results)<len(scopes):
        for scope in scopes:
            if scope['sn'] in results:continue
            value=post(session,'device/state/getLastScan',scope)
            if value.get('inScan'):continue
            assert not value.get('scanError'),value.get('scanError')
            local=[r for f in ('channel24Detail','channel52Detail','channel58Detail')
                   for r in (value.get(f) or {}).get('channelSsidList',[]) if r.get('isSelfSsid')]
            assert local,'Fresh router survey lacks live local BSS records'
            results[scope['sn']]=value
            print(scope['sn'],'local BSSes',len(local),'scanTime',value['scanTime'],flush=True)
        if time.monotonic()>until:raise TimeoutError('Firmware scan did not settle')
        if len(results)<len(scopes):time.sleep(2)
    a,b=list(results.values())
    def own(v):return {r['bssid'] for f in ('channel24Detail','channel52Detail','channel58Detail') for r in v[f]['channelSsidList'] if r['isSelfSsid']}
    assert not own(a)&own(b),'Local BSSID sets leaked across projects'
    (OUT/'hub-scans.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf8')
