"""Real firmware surveys behind the original H5 poll protocol, scoped per router."""
import copy,logging,time
from .commands import UFCommand
from .hub_scan import ScanRunner,payload
from .signal_tools import read

LOG=logging.getLogger('shinya-scan')
RESULT='hub.channel.survey_result'
STATUS='hub.channel.survey_status'

class SignalScan:
    def __init__(self,registry):self.registry=registry;self.runner=ScanRunner(workers=2)
    def start(self,key):
        def job():
            store=self.registry.store
            try:
                with store.atomic():
                    store.save(key.tenant,key.project,key.sn,STATUS,{'state':'running','startedAt':int(time.time()*1000)})
                with self.registry.device_operation(key) as driver:
                    survey=read(driver,'dev_sta','shinya_exec',{'action':'survey','bands':['2.4G','5G']})
                    bands=survey.get('bands')
                    if not isinstance(bands,dict) or any(not isinstance(bands.get(b),list) for b in ('2.4G','5G')):
                        raise RuntimeError('固件扫描未返回完整频段结果')
                    result=payload(survey,[],scan_time=int(time.time()*1000))
                    result['measurementSource']='firmware-site-survey'
                    result['indexAlgorithm']='hub-neighbor-power-v1'
                # Coordinate with telemetry writes, after releasing the radio
                # operation lock. Publish result/status as one short commit.
                with store.atomic():
                    store.save(key.tenant,key.project,key.sn,RESULT,result)
                    store.save(key.tenant,key.project,key.sn,STATUS,{'state':'complete','finishedAt':int(time.time()*1000)})
            except Exception as error:
                LOG.warning('Firmware survey failed for %s: %s',key.sn,type(error).__name__)
                with store.atomic():
                    store.save(key.tenant,key.project,key.sn,STATUS,{'state':'failed','message':str(error)[:160]})
        state=self.runner.start(key,job)
        return {'inScan':True,'state':state}
    def last(self,key):
        store=self.registry.store
        previous=store.cached(key.tenant,key.project,key.sn,RESULT)
        value=copy.deepcopy(previous['value']) if previous else {'proto':0,'scanTime':0,'channel24Detail':None,'channel52Detail':None,'channel58Detail':None}
        value['inScan']=self.runner.running(key)
        status=store.cached(key.tenant,key.project,key.sn,STATUS)
        if status and status['value'].get('state')=='failed':value['scanError']=status['value'].get('message')
        return value
    def close(self):self.runner.shutdown()
