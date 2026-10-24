import importlib.util,sys,tempfile,unittest
from pathlib import Path
from datetime import datetime
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.store import ProjectStore
from shinya_hub.daily import CHINA
spec=importlib.util.spec_from_file_location('shinya_hub.signal_coverage',Path(__file__).with_name('signal_coverage_source.py'))
coverage=importlib.util.module_from_spec(spec);sys.modules[spec.name]=coverage;spec.loader.exec_module(coverage)
daily_spec=importlib.util.spec_from_file_location('shinya_hub.coverage_test_daily',Path('test/_analysis/oct12-signal-coverage/deployment/daily.py'))
daily=importlib.util.module_from_spec(daily_spec);daily_spec.loader.exec_module(daily);record=daily.record

class Coverage(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.temp.name)/'db.sqlite')
        with self.store.connect() as db:coverage.initialize(db)
        self.t=datetime(2026,10,12,9,0,tzinfo=CHINA).timestamp()
    def tearDown(self):self.temp.cleanup()
    def sample(self,seconds,rssi=-88,band='5G',project='p',sn='sn'):
        record(self.store,'t',project,sn,[{'mac':'AA1122334455','userIp':'192.168.1.2','rssi':rssi,'band':band,
               'down':str(100+seconds),'up':'10'}],self.t+seconds)
    def result(self,seconds,project='p'):return coverage.summary(self.store,'t',project,self.t+seconds)['aa:11:22:33:44:55']
    def test_recovered_and_offline_history_has_real_duration(self):
        for s in (0,60,120):self.sample(s)
        self.sample(180,-46)
        v=self.result(180);self.assertTrue(v['isBadSignal']);self.assertEqual(v['badSignalSeconds'],180)
        self.assertEqual(v['badSignalDuration'],'0小时3分');self.assertEqual(v['lastBadSignalTime'],'09:00-09:03')
        record(self.store,'t','p','sn',[],self.t+240)
        self.assertEqual(self.result(250)['badSignalSeconds'],180)
    def test_band_aware_threshold(self):
        self.sample(0,-78,'5G');self.sample(60,-78,'5G');self.assertFalse(self.result(60)['isBadSignal'])
        self.sample(90,-78,'2.4G');self.sample(150,-78,'2.4G');self.assertTrue(self.result(150)['isBadSignal'])
    def test_gaps_duplicates_and_project_isolation(self):
        self.sample(0);self.sample(600);self.sample(660);self.sample(660);self.sample(620)
        self.sample(0,-40,project='other');self.sample(660,-40,project='other')
        self.assertEqual(self.result(660)['badSignalSeconds'],60)
        self.assertFalse(self.result(660,'other')['isBadSignal'])
    def test_latest_episode_differs_from_total_and_survives_restart(self):
        self.sample(0);self.sample(60,-40);self.sample(120,-90);self.sample(180,-40)
        self.store=ProjectStore(Path(self.temp.name)/'db.sqlite')
        v=self.result(180);self.assertEqual(v['badSignalSeconds'],120)
        self.assertEqual(v['lastBadSignalTime'],'09:02-09:03')
    def test_midnight_is_split_and_unknown_signal_is_not_weak(self):
        self.t=datetime(2026,10,12,23,59,30,tzinfo=CHINA).timestamp()
        self.sample(0);self.sample(60,-40)
        self.assertEqual(self.result(60)['badSignalSeconds'],30)
        self.assertEqual(self.result(60)['lastBadSignalTime'],'00:00-00:00')
        self.sample(90,0,project='unknown')
        self.assertEqual(coverage.summary(self.store,'t','unknown',self.t+90),{})

if __name__=='__main__':unittest.main()
