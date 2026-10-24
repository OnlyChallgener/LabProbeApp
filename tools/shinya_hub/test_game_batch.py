import copy,importlib.util,json,sys,tempfile,time,unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.devices import DeviceRegistry,DeviceKey
from shinya_hub.store import ProjectStore

def module(name):
    spec=importlib.util.spec_from_file_location('shinya_hub.'+name,'tools/shinya_hub/'+name+'_source.py')
    mod=importlib.util.module_from_spec(spec);sys.modules[spec.name]=mod;spec.loader.exec_module(mod);return mod
acc=module('game_acceleration');reports=module('game_reports');env=module('game_environment')

class Driver:
    def __init__(self):self.state={'enabled':'true','game':'off','device':'on','study':'off'};self.calls=[];self.reject=False
    def execute(self,c):
        self.calls.append(c)
        if c.module=='rcgame':
            if c.action=='set' and not self.reject:self.state['game']=c.data['game']
            return copy.deepcopy(self.state)
        if c.module=='eweb':return {'gameTurbo':'1'}
        if c.module=='flowctrl':return {'reserve_switch':'on','list':[{'ifname':'br-wan','downloadBand':'1000'}]}
        if c.module=='overview':return {'game':self.state['game']}
        raise AssertionError(c)

class Contracts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.temp.name)/'db')
        self.a=DeviceKey('t','a','A');self.b=DeviceKey('t','b','B')
        for k in (self.a,self.b):self.store.add_project(k.tenant,k.project,k.project);self.store.bind_device(k.tenant,k.project,k.sn,{})
        self.registry=DeviceRegistry(self.store);self.da=Driver();self.db=Driver()
        self.registry.register(self.a,self.da);self.registry.register(self.b,self.db)
        self.reports=reports.GameReports(self.store,self.registry)
    def tearDown(self):self.reports.close();self.registry.close();self.temp.cleanup()
    def test_native_acceleration_readback_preserves_other_services_and_project(self):
        self.assertEqual(0,acc.status(self.registry,self.a)['openStatus'])
        acc.change(self.registry,self.a,True)
        self.assertEqual('on',self.da.state['game']);self.assertEqual('on',self.da.state['device']);self.assertFalse(self.db.calls)
        acc.change(self.registry,self.a,False)
        self.assertEqual('off',self.da.state['game']);self.assertEqual('true',self.da.state['enabled'])
        self.assertTrue(all(c.module in ('rcgame','eweb','flowctrl','overview') for c in self.da.calls))
    def test_rejected_write_is_not_success(self):
        self.da.reject=True
        with self.assertRaises(ValueError):acc.change(self.registry,self.a,True)
    def body(self):
        now=int(time.time());start=now-420
        return {'source':'firmware.rcgame','observedAt':now,
                'events':f'[{start}]:AABBCCDDEEFF 192.168.1.2 play 3-1-1-3 test 1.1.1.1',
                'probes':[{'ipv4':'1.1.1.1','sequence':10,'latency':14},{'ipv4':'192.168.1.2','sequence':9,'latency':2},
                          {'domain':'ys.mihoyo.com','sequence':15,'latency':99}]}
    def test_real_round_events_persist_deduplicate_and_exclude_environment_probes(self):
        body=self.body();self.reports.ingest(self.a,body);self.reports.ingest(self.a,body)
        rows=self.reports.list(self.a);self.assertEqual(1,len(rows));self.assertEqual(14,rows[0]['avgDelay']);self.assertEqual(0,rows[0]['lossRate'])
        self.assertEqual([],self.reports.list(self.b));uuid=rows[0]['uuid']
        with self.assertRaises(LookupError):self.reports.detail(self.b,uuid)
        self.assertEqual(14,reports.GameReports(self.store,self.registry).detail(self.a,uuid)['avgDelay'])
        self.assertEqual(1,len(self.reports.logs(self.a)['gameLogDetailList']))
    def test_firmware_timeouts_and_missing_measurements_remain_distinct(self):
        body=self.body();body['probes']=[];self.reports.ingest(self.a,body)
        row=self.reports.list(self.a)[0];self.assertIsNone(row['avgDelay']);self.assertIsNone(row['lossRate'])
        body['probes']=[{'ipv4':'1.1.1.1','sequence':3,'latency':65535}];self.reports.ingest(self.a,body)
        row=self.reports.list(self.a)[0];self.assertIsNone(row['avgDelay']);self.assertEqual(100,row['lossRate'])
        body['events']+=f"\n[{int(time.time())}]:AABBCCDDEEFF 192.168.1.2 leave_game 3-1-1-3 test 1.1.1.1"
        self.reports.ingest(self.a,body);row=self.reports.list(self.a)[0];self.assertEqual(0,row['inGaming']);self.assertGreaterEqual(row['gameDuration'],120)
    def test_api_write_permissions_report_scope_and_assets(self):
        self.reports.ingest(self.a,self.body())
        spec=importlib.util.spec_from_file_location('shinya_hub.api_game_batch','test/_analysis/oct12-game-batch/deployment/api.py')
        api=importlib.util.module_from_spec(spec);sys.modules[spec.name]=api;spec.loader.exec_module(api)
        app=api.create_app(self.store,self.registry,{api.token_digest('write'):api.Principal('t','write',True),api.token_digest('read'):api.Principal('t','read',False)})
        client=app.test_client()
        try:
            req={'projectId':'a','sn':'A'};head={'Authorization':'Bearer write'}
            response=client.post('/oneStepGame/startFastAccelerate',json=req,headers={'Authorization':'Bearer read'}).get_json();self.assertEqual(403,response['code'])
            response=client.post('/oneStepGame/startFastAccelerate',json={**req,'projectId':'b'},headers=head).get_json();self.assertEqual(404,response['code']);self.assertFalse(self.db.calls)
            response=client.post('/homewlan/eSportAccelerate/getGameAccelerateHistoryNew',json=req,headers=head).get_json();self.assertEqual(0,response['code']);self.assertEqual(1,len(response['data']))
            uuid=response['data'][0]['uuid'];response=client.post('/api/v1/eSportAccelerate/getHistoryDetail',json={'projectId':'b','sn':'B','uuid':uuid},headers=head).get_json();self.assertEqual(404,response['code'])
            self.assertEqual(404,client.get('/app-h5-hub/other').status_code)
        finally:
            for name in ('shinya_game_reports','shinya_game_environment','shinya_children','shinya_diagnosis'):app.extensions[name].close()

if __name__=='__main__':unittest.main()
