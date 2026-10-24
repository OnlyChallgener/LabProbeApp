"""Independent router push channel. Official cloud processes are never reconfigured."""
import json
import logging
import math
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .commands import UFCommand
from .daily import record as record_daily
from .devices import DeviceKey, DeviceRegistry
from .identifiers import mac_key
from .statistics import quality_value, record_resource
from .store import ProjectStore
from .telemetry import STATION_RATES, station_rates, wan_rates
from .vault import ConfigVault

MODULES={'sysinfo','user_list','flow','overview','radioInfo','ap_list'}
LOG=logging.getLogger('shinya-push')

class PushIngestor:
    def __init__(self,store,bindings):
        self.store=store
        self.bindings={d['sn']:DeviceKey(d['tenant'],d['project'],d['sn']) for d in bindings}
        if len(self.bindings)!=len(bindings):raise ValueError('A router needs a unique binding')
        self.locks={sn:threading.Lock() for sn in self.bindings}
        with store.connect() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS push_runs (
                tenant TEXT,project TEXT,sn TEXT,run TEXT,sequence INTEGER,received REAL,
                PRIMARY KEY(tenant,project,sn,run));
                CREATE TABLE IF NOT EXISTS push_transport (
                tenant TEXT,project TEXT,sn TEXT,received REAL,observed REAL,sequence INTEGER,
                resources INTEGER,PRIMARY KEY(tenant,project,sn));''')

    def ingest(self,topic,payload,received=None):
        received=time.time() if received is None else received
        match=re.fullmatch(r'shinya/v1/([A-Za-z0-9_-]{1,64})/up',topic)
        if not match or match[1] not in self.bindings:raise PermissionError('Unbound router topic')
        if len(payload)>1024*1024:raise ValueError('Frame is too large')
        frame=json.loads(payload)
        sn=match[1];key=self.bindings[sn]
        if frame.get('version')!=1 or frame.get('sn')!=sn:raise ValueError('Wrong frame identity')
        run=frame.get('runId');seq=frame.get('sequence')
        if not isinstance(run,str) or not re.fullmatch(r'[A-Za-z0-9:-]{8,128}',run):raise ValueError('Invalid run ID')
        if type(seq)!=int or not 0<seq<2**53:raise ValueError('Invalid sequence')
        resources=frame.get('resources')
        if not isinstance(resources,list) or not 1<=len(resources)<=len(MODULES):raise ValueError('Invalid resources')
        validated=[];seen=set()
        for item in resources:
            module=item.get('module');value=item.get('value');observed=item.get('observedAt')
            if module not in MODULES or module in seen:raise ValueError('Unknown or repeated resource')
            seen.add(module)
            if type(observed) not in (int,float) or not math.isfinite(observed) or not received-120<=observed<=received+5:
                raise ValueError('Untrusted observation clock')
            data={'func':'interface_info'} if module=='flow' else None
            command=UFCommand('dev_sta','get',module,data)
            if not isinstance(value,dict):raise ValueError('Invalid native value')
            if module=='sysinfo':
                if str(value.get('serial_num') or value.get('serialNumber') or value.get('sn') or '')!=sn:
                    raise ValueError('Physical router identity differs')
            elif module=='user_list':
                rows=value.get('list')
                if not isinstance(rows,list) or not all(isinstance(r,dict) and mac_key(r.get('mac')) for r in rows):
                    raise ValueError('Incomplete station snapshot')
            elif module=='flow':wan_rates(value)
            elif module in ('overview','radioInfo'):quality_value(module,value)
            validated.append((command,value,observed))
        # Each device is serialized, so the cursor and complete native lists
        # cannot overtake each other. MQTT QoS 1 retransmissions are idempotent.
        with self.locks[sn], self.store.atomic():
            if not self.store.owned_device(key.tenant,key.project,key.sn):raise PermissionError('Router binding was removed')
            with self.store.connect() as db:
                row=db.execute('SELECT sequence FROM push_runs WHERE tenant=? AND project=? AND sn=? AND run=?',
                               (key.tenant,key.project,sn,run)).fetchone()
                if row and seq<=row['sequence']:return {'accepted':False,'reason':'duplicate'}
            accepted=0;identity_accepted=False
            for command,value,observed in validated:
                resource=DeviceRegistry.resource(command)
                previous=self.store.cached(key.tenant,key.project,sn,resource)
                if previous and observed<=previous['observedAt']:continue
                if command.module=='user_list':
                    old_rows=previous['value'].get('list',[]) if previous else []
                    elapsed=observed-previous['observedAt'] if previous else 0
                    rates=station_rates(value['list'],old_rows,elapsed)
                    record_daily(self.store,key.tenant,key.project,sn,value['list'],observed)
                    self.store.merge_stations(key.tenant,key.project,sn,value['list'],seen=observed)
                    self.store.save(key.tenant,key.project,sn,STATION_RATES,rates,observed=observed)
                record_resource(self.store,key,command,value,observed)
                self.store.save(key.tenant,key.project,sn,resource,value,observed=observed)
                if command.module=='sysinfo':identity_accepted=True
                accepted+=1
            with self.store.connect() as db:
                db.execute('''INSERT INTO push_runs VALUES(?,?,?,?,?,?) ON CONFLICT(tenant,project,sn,run)
                    DO UPDATE SET sequence=excluded.sequence,received=excluded.received''',
                    (key.tenant,key.project,sn,run,seq,received))
                db.execute('''INSERT INTO push_transport VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant,project,sn)
                    DO UPDATE SET received=excluded.received,observed=MAX(push_transport.observed,excluded.observed),
                    sequence=excluded.sequence,resources=excluded.resources''',
                    (key.tenant,key.project,sn,received,max(v[2] for v in validated),seq,accepted))
                db.execute('DELETE FROM push_runs WHERE received<?',(received-30*86400,))
            if identity_accepted:self.store.health(key.tenant,key.project,sn,True,checked=max(v[2] for v in validated))
            return {'accepted':True,'resources':accepted,'sequence':seq}

class LatestQueue:
    """One pending telemetry frame per registered router, bounded by bindings."""
    def __init__(self,ingestor):
        self.ingestor=ingestor;self.pool=ThreadPoolExecutor(max_workers=6)
        self.lock=threading.Lock();self.pending={};self.active=set()
    def submit(self,topic,payload):
        parts=topic.split('/')
        if len(parts)!=4 or parts[2] not in self.ingestor.bindings or len(payload)>1024*1024:return
        sn=parts[2]
        with self.lock:
            self.pending[sn]=(topic,bytes(payload),time.time())
            if sn not in self.active:
                self.active.add(sn);self.pool.submit(self.drain,sn)
    def drain(self,sn):
        while True:
            with self.lock:
                item=self.pending.pop(sn,None)
                if item is None:self.active.remove(sn);return
            try:self.ingestor.ingest(*item)
            except Exception as e:LOG.warning('Rejected router frame: %s %s',sn,type(e).__name__)

def main():
    import paho.mqtt.client as mqtt
    logging.basicConfig(level=logging.INFO)
    config=ConfigVault(Path('/config')).load();settings=config['mqttSync']
    store=ProjectStore(Path('/data/shinya.sqlite'))
    queue=LatestQueue(PushIngestor(store,config['devices']))
    client=mqtt.Client(client_id='shinya-hub-ingest-v1')
    client.username_pw_set(settings['username'],settings['password'])
    client.tls_set(ca_certs='/config/mqtt-ca.crt')
    client.reconnect_delay_set(min_delay=2,max_delay=60)
    def on_connect(c,userdata,flags,rc):
        if rc==0:
            for sn in queue.ingestor.bindings:c.subscribe('shinya/v1/'+sn+'/up',qos=1)
            LOG.info('Independent router push channel connected')
    client.on_connect=on_connect
    client.on_message=lambda c,u,m:queue.submit(m.topic,m.payload)
    client.connect(settings.get('host','127.0.0.1'),settings.get('port',58545),keepalive=60)
    client.loop_forever(retry_first_connection=True)

if __name__=='__main__':main()
