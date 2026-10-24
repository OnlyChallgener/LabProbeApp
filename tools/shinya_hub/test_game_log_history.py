"""Real event formats, upgrade deduplication, scoping, and measured series."""
import hashlib,json,sys,tempfile,time,unittest
from datetime import datetime,timedelta
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.devices import DeviceRegistry,DeviceKey
from shinya_hub.store import ProjectStore
from shinya_hub.game_reports import GameReports,GUARD_WRITER_SHA
from shinya_hub.daily import CHINA

class History(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.temp.name)/'db')
  self.a=DeviceKey('t','a','A');self.b=DeviceKey('t','b','B')
  for k in (self.a,self.b):self.store.add_project(k.tenant,k.project,k.project);self.store.bind_device(k.tenant,k.project,k.sn,{})
  self.registry=DeviceRegistry(self.store);self.reports=GameReports(self.store,self.registry)
 def tearDown(self):self.reports.close();self.registry.close();self.temp.cleanup()
 def ingest(self,text,probes=None):
  self.reports.ingest(self.a,{'source':'firmware.rcgame','observedAt':int(time.time()),'events':text,'probes':probes or []})
 def test_status_upload_same_event_and_upgrade_do_not_duplicate_round(self):
  at=int(time.time())-420;line=f'[{at}]:AABBCCDDEEFF 192.168.1.2 play 3-1-1-3 game 1.1.1.1'
  self.ingest(line)
  with self.store.connect() as db:db.execute('UPDATE game_events SET id=?',(hashlib.sha256(line.encode()).hexdigest(),))
  self.ingest(line+f'\n{at} aabbccddeeff 192.168.1.2 play 3-1-1-3 game 1.1.1.1')
  self.assertEqual(1,len(self.reports.list(self.a)))
  self.assertEqual(1,len(self.reports.logs(self.a)['gameLogDetailList']))
  self.assertEqual([],self.reports.list(self.b))
 def test_air_control_upload_is_real_action_not_a_round(self):
  at=int(time.time())-2
  self.ingest(f'air_fc {at} AABBCCDDEEFF 192.168.1.2 add_air_flowctrl group 70 60')
  self.assertEqual([],self.reports.list(self.a))
  value=self.reports.acceleration_statistics(self.a)
  self.assertEqual(1,value['antiOccupy']['todayCnt']);self.assertIsNone(value['antiShake']['todayCnt'])
  self.assertIn('AI流控',self.reports.logs(self.a)['gameLogDetailList'][0]['eventTitle'])
 def test_noise_no_channel_change_no_count_and_calendar_seven_days(self):
  today=datetime.now(CHINA).replace(hour=0,minute=0,second=0,microsecond=0)
  self.reports.channel_action(self.a,'5G',{'beforeChannel':52,'afterChannel':52})
  for at in (today-timedelta(days=6)+timedelta(seconds=1),today-timedelta(days=7),datetime.now(CHINA)-timedelta(seconds=1)):
   self.reports.channel_action(self.a,'5G',{'beforeChannel':60,'afterChannel':52,'optimizedAt':int(at.timestamp()*1000)})
  value=self.reports.acceleration_statistics(self.a)['antiBother']
  self.assertEqual(2,value['historyCnt']);self.assertEqual(1,value['todayCnt'])
  self.assertEqual(0,self.reports.acceleration_statistics(self.b)['antiBother']['historyCnt'])
 def test_detail_aligns_measurements_and_missing_is_not_zero(self):
  at=int(time.time())-420
  text=f'[{at}]:AABBCCDDEEFF 192.168.1.2 play 3-1-1-3 game 1.1.1.1'
  self.ingest(text,[{'ipv4':'1.1.1.1','sequence':1,'latency':14},{'ipv4':'192.168.1.2','sequence':1,'latency':2}])
  uuid=self.reports.list(self.a)[0]['uuid'];value=self.reports.detail(self.a,uuid)
  self.assertEqual([14],value['apNetDelayList']);self.assertEqual([2],value['staDelayList']);self.assertEqual([16],value['totalDelayList'])
  self.assertEqual(16,value['avgDelay']);self.assertEqual(16,self.reports.list(self.a)[0]['avgDelay'])
  self.assertIsNone(value['gamePriority'])
  with self.assertRaises(LookupError):self.reports.detail(self.b,uuid)
 def test_unpaired_samples_and_timeout_do_not_become_total_delay(self):
  at=int(time.time())-420
  self.ingest(f'[{at}]:AABBCCDDEEFF 192.168.1.2 play 3-1-1-3 game 1.1.1.1',[{'ipv4':'1.1.1.1','sequence':1,'latency':14}])
  value=self.reports.list(self.a)[0];self.assertIsNone(value['avgDelay'])
  self.assertEqual([None],self.reports.detail(self.a,value['uuid'])['totalDelayList'])
 def test_guard_activation_is_scoped_deduplicated_and_not_daemon_startup(self):
  now=int(time.time())
  body={'source':'firmware.rcgame','observedAt':now,'guardLogAvailable':True,'guardWriterSha256':GUARD_WRITER_SHA,
        'guardEvents':[{'Time':str(now-4+i),'StaMac':'AABBCCDDEEFF','EventType':str(i),'AppId':'4-1-1'} for i in range(1,5)]}
  self.reports.ingest(self.a,body);self.reports.ingest(self.a,body)
  stats=self.reports.acceleration_statistics(self.a)['antiShake']
  self.assertEqual(1,stats['todayCnt']);self.assertEqual(1,stats['historyCnt'])
  self.assertEqual(4,len(self.reports.logs(self.a)['gameLogDetailList']))
  self.assertIsNone(self.reports.acceleration_statistics(self.b)['antiShake']['todayCnt'])
 def test_unverified_writer_and_invalid_events_are_not_counted(self):
  now=int(time.time());item={'Time':str(now),'StaMac':'AABBCCDDEEFF','EventType':'1'}
  body={'source':'firmware.rcgame','observedAt':now,'guardLogAvailable':True,'guardWriterSha256':'unknown','guardEvents':[item]}
  self.reports.ingest(self.a,body);self.assertIsNone(self.reports.acceleration_statistics(self.a)['antiShake']['todayCnt'])
  body['guardWriterSha256']=GUARD_WRITER_SHA;body['guardEvents']=[{**item,'EventType':'99'},{**item,'Time':'bad'},{**item,'StaMac':'bad'}]
  self.reports.ingest(self.a,body);self.assertEqual(0,self.reports.acceleration_statistics(self.a)['antiShake']['todayCnt'])

if __name__=='__main__':unittest.main()
