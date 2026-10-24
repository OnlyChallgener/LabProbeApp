"""Check only owned push services, public counters, and authenticated read APIs."""
import json,os,sys,time
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run
from remote_transport import connect_nas

OUT=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/cloud-sync'
PROBE=r'''
import json,time,ssl,socket
from pathlib import Path
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceRegistry
from shinya_hub.statistics import speed,stability
c=ConfigVault(Path('/config')).load();store=ProjectStore(Path('/data/shinya.sqlite'))
ctx=ssl.create_default_context(cafile='/config/mqtt-ca.crt')
with ctx.wrap_socket(socket.socket(),server_hostname='127.0.0.1') as s:
    s.settimeout(3);s.connect(('127.0.0.1',58545));tls=s.version()
routers=[]
for d in c['devices']:
    with store.connect() as db:
        push=db.execute('SELECT * FROM push_transport WHERE tenant=? AND project=? AND sn=?',(d['tenant'],d['project'],d['sn'])).fetchone()
    native=store.cached(d['tenant'],d['project'],d['sn'],DeviceRegistry.resource(UFCommand('dev_sta','get','user_list',None)))
    rates=speed(store,d['tenant'],d['project'],d['sn']);quality=stability(store,d['tenant'],d['project'],d['sn'])
    routers.append({'family':d['family'],'sn':d['sn'],'push':None if not push else {
        'sequence':push['sequence'],'ageSeconds':round(time.time()-push['observed'],2),'resources':push['resources']},
        'cachedNativeStations':len(native['value']['list']) if native else None,
        'archivedStations':len(store.stations(d['tenant'],d['project'])),
        'curveBuckets':len(rates['bucketIndices']),'stabilityScore':quality['curRadarScore'].get('overallScore'),
        'scoreDimensions':len(quality['metrics'])})
print(json.dumps({'tls':tls,'routers':routers}))
'''
HTTP_PROBE=r'''
import json,time,os,yaml,requests
from pathlib import Path
cfg_path=Path(os.environ.get('CONFIG_PATH','/app/config/config.yaml'))
cfg=yaml.safe_load(cfg_path.read_text()) if cfg_path.is_file() else {}
token=os.environ.get('APP_TOKEN') or cfg.get('server',{}).get('app_token')
if not token:raise ValueError('Existing APP token unavailable')
s=requests.Session();s.trust_env=False;headers={'Authorization':'Bearer '+token}
base='http://127.0.0.1:58543'
def post(path,body):
    start=time.monotonic();v=s.post(base+path,json=body,headers=headers,timeout=5).json()
    if v.get('code')!=0:raise ValueError('Known read API rejected: '+path+' code='+str(v.get('code')))
    return v['data'],round((time.monotonic()-start)*1000,1)
projects,_=post('/homewlan/apMonitor/getProjectList2',{})
out=[]
for p in projects['dataList']:
    rows,ms=post('/homewlan/homeAp/getStaList2',{'projectId':p['projectId']})
    rate,rms=post('/homewlan/experienceVisual/getApSpeedRate',{'projectId':p['projectId']})
    quality,qms=post('/homewlan/experienceVisual/getNetStabilizeIndex',{'projectId':p['projectId']})
    out.append({'project':p.get('projectName') or p.get('name'),'stations':len(rows['sta_list']),
        'responseMs':ms,'curveResponseMs':rms,'stabilityResponseMs':qms,
        'source':rate.get('source'),'curveBuckets':len(rate.get('bucketIndices',[])),
        'score':quality.get('curRadarScore',{}).get('overallScore')})
print(json.dumps(out,ensure_ascii=False))
'''

def main():
    pw=os.environ['SHINYA_NAS_PASSWORD']
    c=connect_nas()
    try:
        result={'capturedAt':time.time()}
        result['services']=run(c,"docker ps -a --format '{{.Names}} {{.Status}}' --filter name=shinya",sudo_password=pw).decode()
        result['metrics']=run(c,"docker stats --no-stream --format '{{.Name}} CPU={{.CPUPerc}} MEM={{.MemUsage}} NET={{.NetIO}}' shinya-mqtt shinya-hub-push shinya-hub",sudo_password=pw,timeout=20).decode()
        result['workerLog']=run(c,"sh -c 'docker logs --tail 12 shinya-hub-push 2>&1'",sudo_password=pw).decode()
        result['brokerLog']=run(c,"sh -c 'docker logs --tail 8 shinya-mqtt 2>&1'",sudo_password=pw).decode()
        result['state']=json.loads(run(c,'docker exec -i shinya-hub python -',stdin=PROBE.encode(),sudo_password=pw,timeout=15))
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/'independent-sync-latest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False),flush=True)
        result['api']=json.loads(run(c,'docker exec -i labprobe-hub python -',stdin=HTTP_PROBE.encode(),sudo_password=pw,timeout=20))
        (OUT/'independent-sync-latest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        (OUT/('sync-check-'+str(int(result['capturedAt']))+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False),flush=True)
    finally:c.close()

if __name__=='__main__':main()
