import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from shinya_hub.commands import UFCommand
from shinya_hub.daily import summary
from shinya_hub.devices import DeviceRegistry
from shinya_hub.mqtt_ingest import PushIngestor
from shinya_hub.store import ProjectStore

class PushTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.temp.name)/'push.sqlite')
        self.bindings=[{'tenant':'owner','project':'home','sn':'A'},{'tenant':'other','project':'home','sn':'B'}]
        for d in self.bindings:
            self.store.add_project(d['tenant'],d['project'],'Home');self.store.bind_device(d['tenant'],d['project'],d['sn'],{})
        self.ingestor=PushIngestor(self.store,self.bindings)
        self.at=1791517200
    def tearDown(self):self.temp.cleanup()
    def frame(self,seq,at,down=100,sn='A'):
        return json.dumps({'version':1,'sn':sn,'runId':'test-run-1','sequence':seq,'resources':[
            {'module':'sysinfo','observedAt':at,'value':{'serial_num':sn}},
            {'module':'user_list','observedAt':at,'value':{'list':[{'mac':'AA1122334455','down':str(down),'up':'0','userIp':'x','band':'5G','rxrate':'909M','rssi':'-55'}]}}
        ]})
    def test_native_snapshot_reuses_cache_and_daily_ledger_once(self):
        self.ingestor.ingest('shinya/v1/A/up',self.frame(1,self.at),self.at)
        self.ingestor.ingest('shinya/v1/A/up',self.frame(2,self.at+30,200),self.at+30)
        self.assertEqual(summary(self.store,'owner','home',self.at)['bytes'],100)
        self.assertFalse(self.ingestor.ingest('shinya/v1/A/up',self.frame(2,self.at+30,200),self.at+30)['accepted'])
        self.assertEqual(summary(self.store,'owner','home',self.at)['bytes'],100)
        self.assertFalse(self.store.stations('other','home'))
    def test_topic_body_identity_clock_and_malformed_list_cannot_erase_cache(self):
        self.ingestor.ingest('shinya/v1/A/up',self.frame(1,self.at),self.at)
        for topic,payload in [('shinya/v1/B/up',self.frame(2,self.at)),('shinya/v1/X/up',self.frame(2,self.at)),
                              ('shinya/v1/A/up',self.frame(2,self.at+999))]:
            with self.assertRaises((ValueError,PermissionError)):self.ingestor.ingest(topic,payload,self.at)
        bad=json.loads(self.frame(2,self.at+30));bad['resources'][1]['value']['list']={}
        with self.assertRaises(ValueError):self.ingestor.ingest('shinya/v1/A/up',json.dumps(bad),self.at+30)
        resource=DeviceRegistry.resource(UFCommand('dev_sta','get','user_list',None))
        self.assertEqual(len(self.store.cached('owner','home','A',resource)['value']['list']),1)
    def test_restart_stale_frame_and_missing_frame_do_not_create_traffic_or_offline(self):
        self.ingestor.ingest('shinya/v1/A/up',self.frame(1,self.at),self.at)
        self.ingestor.ingest('shinya/v1/A/up',self.frame(2,self.at+30,200),self.at+30)
        stale=json.loads(self.frame(1,self.at+10,9999));stale['runId']='older-run-2'
        self.ingestor.ingest('shinya/v1/A/up',json.dumps(stale),self.at+30)
        self.assertEqual(summary(self.store,'owner','home',self.at)['bytes'],100)
        restarted=PushIngestor(self.store,self.bindings)
        self.assertFalse(restarted.ingest('shinya/v1/A/up',self.frame(1,self.at),self.at+30)['accepted'])
        self.assertEqual(len(self.store.stations('owner','home')),1)
        self.assertTrue(self.store.push_fresh('owner','home','A',self.at+100))
        self.assertFalse(self.store.push_fresh('owner','home','A',self.at+121))
        self.assertFalse(self.store.push_fresh('other','home','A',self.at+100))

    def test_delayed_pull_save_cannot_rewind_push_snapshot(self):
        self.ingestor.ingest('shinya/v1/A/up',self.frame(2,self.at+30,200),self.at+30)
        resource=DeviceRegistry.resource(UFCommand('dev_sta','get','user_list',None))
        self.store.save('owner','home','A',resource,{'list':[]},observed=self.at+10)
        self.assertEqual(len(self.store.cached('owner','home','A',resource)['value']['list']),1)

    def test_failed_storage_rolls_back_complete_frame_and_cursor(self):
        original=self.store.save
        def fail_station(*args,**kwargs):
            if 'user_list' in args[3]:raise RuntimeError('storage fixture')
            return original(*args,**kwargs)
        with patch.object(self.store,'save',side_effect=fail_station):
            with self.assertRaises(RuntimeError):self.ingestor.ingest('shinya/v1/A/up',self.frame(1,self.at),self.at)
        resource=DeviceRegistry.resource(UFCommand('dev_sta','get','sysinfo',None))
        self.assertIsNone(self.store.cached('owner','home','A',resource))
        self.assertFalse(self.store.stations('owner','home'))
        self.assertTrue(self.ingestor.ingest('shinya/v1/A/up',self.frame(1,self.at),self.at)['accepted'])

if __name__=='__main__':unittest.main()
