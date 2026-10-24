"""Synthetic load with actual native schemas, entirely in a temporary database."""
import json,os,sys
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run
from remote_transport import connect_nas

PROBE=r'''
import copy,json,tempfile,time,statistics,resource
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from shinya_hub.vault import ConfigVault
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceRegistry
from shinya_hub.commands import UFCommand
from shinya_hub.mqtt_ingest import PushIngestor
c=ConfigVault(Path('/config')).load();real=ProjectStore(Path('/data/shinya.sqlite'))
templates=[]
for d in c['devices']:
    data=[]
    for module in ('sysinfo','user_list','flow','overview','radioInfo','ap_list'):
        command=UFCommand('dev_sta','get',module,{'func':'interface_info'} if module=='flow' else None)
        value=real.cached(d['tenant'],d['project'],d['sn'],DeviceRegistry.resource(command))
        if value:data.append({'module':module,'value':value['value']})
    templates.append(data)
with tempfile.TemporaryDirectory(prefix='sync-load-',dir='/data') as temporary:
    store=ProjectStore(Path(temporary)/'benchmark.sqlite')
    bindings=[{'tenant':'load-user-'+str(i//2),'project':'load-project-'+str(i),'sn':'LOAD'+str(i).zfill(4)} for i in range(100)]
    for d in bindings:
        store.add_project(d['tenant'],d['project'],'Load fixture');store.bind_device(d['tenant'],d['project'],d['sn'],{})
    ingestor=PushIngestor(store,bindings);timings=[];sizes=[];completion=[];failures=[]
    def frame(index,sequence):
        d=bindings[index];at=1791517200+sequence*5
        parts=copy.deepcopy(templates[index%len(templates)])
        for part in parts:
            part['observedAt']=at
            if part['module']=='sysinfo':part['value']['serial_num']=d['sn']
            if part['module']=='user_list':
                native=part['value']['list'];rows=[]
                for j in range(30):
                    row=copy.deepcopy(native[j%len(native)]);row['mac']=format(j+1,'012X');row['state']='1'
                    row['up']=str(10000+sequence*100);row['down']=str(100000+sequence*1000);rows.append(row)
                part['value']['list']=rows;part['value']['total']=30
        payload=json.dumps({'version':1,'sn':d['sn'],'runId':'isolated-load-run','sequence':sequence,'resources':parts}).encode()
        start=time.perf_counter()
        try:ingestor.ingest('shinya/v1/'+d['sn']+'/up',payload,received=at)
        except Exception as e:failures.append(type(e).__name__)
        return (time.perf_counter()-start)*1000,len(payload),(time.perf_counter()-round_start)*1000
    cpu=time.process_time();start=time.perf_counter();rounds=[]
    with ThreadPoolExecutor(max_workers=6) as pool:
        for seq in range(1,4):
            before=time.perf_counter();round_start=before
            for ms,size,latency in pool.map(lambda i:frame(i,seq),range(100)):
                timings.append(ms);sizes.append(size);completion.append(latency)
            rounds.append(round(time.perf_counter()-before,3))
    elapsed=time.perf_counter()-start
    timings.sort();completion.sort();mean_bytes=statistics.mean(sizes)
    print(json.dumps({'scope':'isolated-synthetic-ingestion-not-network-benchmark','devices':100,'stationsPerDevice':30,
        'frames':len(timings),'failures':failures,'burstRoundSeconds':rounds,'elapsedSeconds':round(elapsed,3),
        'cpuSeconds':round(time.process_time()-cpu,3),'throughputFramesPerSecond':round(len(timings)/elapsed,1),
        'frameProcessingP50Ms':round(statistics.median(timings),2),'frameProcessingP95Ms':round(timings[int(.95*(len(timings)-1))],2),
        'burstCompletionP95Ms':round(completion[int(.95*(len(completion)-1))],2),
        'payloadMeanBytes':round(mean_bytes),'fullPayloadAt5SecondsMbps':round(mean_bytes*100*8/5/1e6,3),
        'maxRssMiB':round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024,1)}))
'''

def main():
    pw=os.environ['SHINYA_NAS_PASSWORD']
    c=connect_nas()
    try:
        value=json.loads(run(c,'docker exec -i shinya-hub python -',stdin=PROBE.encode(),sudo_password=pw,timeout=55))
        target=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/cloud-sync/load-100-routers.json'
        target.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(value,ensure_ascii=False))
    finally:c.close()

if __name__=='__main__':main()
