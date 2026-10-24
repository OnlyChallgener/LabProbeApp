import copy
import importlib.util
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.devices import DeviceRegistry,DeviceKey
from shinya_hub.store import ProjectStore

spec=importlib.util.spec_from_file_location('shinya_hub.game_environment','tools/shinya_hub/game_environment_source.py')
game=importlib.util.module_from_spec(spec);sys.modules[spec.name]=game;spec.loader.exec_module(game)

class Driver:
    def __init__(self):self.calls=[];self.fail=False
    def execute(self,c):
        self.calls.append(c)
        if c.module=='eweb':return {'gameTurbo':'1'}
        if c.module=='rcgame':
            if c.action=='set':self.game=c.data['game']
            return {'game':getattr(self,'game','off'),'enabled':'true'}
        if self.fail and c.module=='radio_channel':raise ValueError('Radio unavailable')
        return copy.deepcopy({
            'user_list':{'list':[{'mac':'AA','connectType':'wireless','rssi':'-55'}]},
            'radio_channel':{'band':'2.4G,5G','chutil':'30,10','channf':'-90,-94','cur_channel':'1,36'},
            'overview':{'cpuutil':'20%','memutil':'65','conntrack_count':10,'conntrack_max':100,'status':'connected','down_rate':1000,'up_rate':0},
            'port_status':{'List':[{'name':'WAN','status':'on','speed':'1000'}]},
            'shinya_game':{'rows':[{'host':'ys.mihoyo.com','status':'success','avg':12.5,'lossRate':0,'sent':3,'received':3}]},
        }[c.module])

class Contracts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.temp.name)/'db')
        for pid,sn in [('p','A'),('q','B')]:self.store.add_project('t',pid,pid);self.store.bind_device('t',pid,sn,{})
        self.r=DeviceRegistry(self.store,workers=1);self.key=DeviceKey('t','p','A');self.other=DeviceKey('t','q','B')
        self.d=Driver();self.r.register(self.key,self.d);self.r.register(self.other,Driver());self.g=game.GameEnvironment(self.r)
    def tearDown(self):self.g.close();self.r.close();self.temp.cleanup()
    def wait(self,job,field='allCheckStatus'):
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            value=self.g.detail(self.key,job)
            if value.get(field):return value
            time.sleep(.01)
        self.fail('Job did not complete')
    def test_all_steps_complete_from_firmware_and_publish_card(self):
        value=self.wait(self.g.start(self.key))
        self.assertEqual('success',value['allCheckStatus']);self.assertEqual(set(game.TAGS),set(value['detailMap']))
        self.assertEqual(12.5,value['beforeDelay']);self.assertEqual(0,value['beforeLossRate'])
        card=self.g.current(self.key);self.assertEqual(10,card['botherIndex']);self.assertEqual(0,card['weakSignalIndex'])
        self.assertTrue(all(c.action=='get' for c in self.d.calls));self.assertIsNone(self.g.current(self.other))
    def test_wrong_project_cannot_poll_and_last_result_survives_job_memory(self):
        value=self.wait(self.g.start(self.key))
        with self.assertRaises(PermissionError):self.g.detail(self.other,value['id'])
        self.g.jobs.clear();self.assertEqual(value,self.g.detail(self.key,value['id']))
        with self.assertRaises(LookupError):self.g.detail(self.other,value['id'])
    def test_failed_read_is_failure_with_explanation_not_healthy_zero(self):
        self.d.fail=True;value=self.wait(self.g.start(self.key))
        self.assertEqual('fail',value['allCheckStatus']);row=value['detailMap']['wifi']
        self.assertEqual('firmware-read-failure',row['source']);self.assertIn('Radio unavailable',row['checkSummary'])
        self.assertIsNone(self.g.current(self.key)['botherIndex'])
    def test_live_weak_signal_and_nonreply_probes_are_not_reported_normal(self):
        value=game.station_result({'list':[{'mac':'AA','name':'weak','type':'phone','connectType':'wireless','rssi':'-77'}]})
        self.assertEqual('fail',value['checkStatus']);self.assertEqual(100,value['basis']['weakPercent'])
        value=game.game_result({'rows':[{'host':'x','status':'fail','sent':3,'received':0,'lossRate':100}]})
        self.assertEqual('fail',value['checkStatus']);self.assertIsNone(value['basis']['bestProbe'])
        self.assertIn('ICMP',value['checkSummary'])
    def test_game_and_child_mode_are_valid_connected_wan_states(self):
        for status in ('connected','rcgame','healthy'):
            row=game.net_result({'status':status,'down_rate':1000,'up_rate':100},
                                {'List':[{'name':'WAN','status':'on','speed':'1000'}]})
            self.assertEqual('success',row['checkStatus']);self.assertTrue(row['basis']['wanConnected'])
    def test_optimization_does_not_touch_feature_library_or_unrelated_device(self):
        value=self.wait(self.g.start(self.key));self.d.calls.clear()
        self.g.optimize(self.key,value['id']);after=self.wait(value['id'],'allOptimizeStatus')
        self.assertEqual('success',after['allOptimizeStatus']);self.assertIsNone(self.g.last(self.other))
        writes=[c for c in self.d.calls if c.action!='get']
        self.assertEqual([('dev_sta','set','rcgame',{'enabled':'default','game':'on'})],[(c.namespace,c.action,c.module,c.data) for c in writes])
    def test_api_original_and_v1_paths_enforce_scope(self):
        spec=importlib.util.spec_from_file_location('shinya_hub.api_game','test/_analysis/oct12-game-environment/deployment/api.py')
        api=importlib.util.module_from_spec(spec);sys.modules[spec.name]=api;spec.loader.exec_module(api)
        app=api.create_app(self.store,self.r,{api.token_digest('test'):api.Principal('t','test',True)})
        client=app.test_client();headers={'Authorization':'Bearer test'}
        try:
            r=client.post('/game/gameEnvCheck/startCheck',json={'projectId':'p','sn':'A'},headers=headers).get_json()
            self.assertEqual(0,r['code']);job=r['data'];self.assertTrue(job)
            r=client.post('/api/v1/game/gameEnvCheck/getCheckDetail',json={'projectId':'q','sn':'B','checkId':job},headers=headers).get_json()
            self.assertEqual(403,r['code'])
            r=client.post('/game/accelerate/getCurrentGameEnv',json={'projectId':'q','sn':'A'},headers=headers).get_json()
            self.assertEqual(404,r['code'])
        finally:
            app.extensions['shinya_game_environment'].close();app.extensions['shinya_children'].close();app.extensions['shinya_diagnosis'].close()

if __name__=='__main__':unittest.main()
