"""Completed-survey panel time and shared optimization event publication."""
import copy,json,sys,tempfile,time,unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub import signal_tools
from shinya_hub.game_reports import GameReports,record_channel_action
from shinya_hub.devices import DeviceRegistry,DeviceKey
from shinya_hub.store import ProjectStore
from shinya_hub.signal_scan import RESULT
from shinya_hub.daily import CHINA
from shinya_hub.api import create_app,Principal,token_digest
from datetime import datetime

class Driver:
 def __init__(self):self.radio={'radioList':[{'radioIndex':'1','type':'2.4G','channel':'13'},{'radioIndex':'2','type':'5G','channel':'64'}]};self.reject=False;self.writes=0
 def execute(self,c):
  if c.module=='radio_channel':return {'band':'2.4G,5G','cur_channel':','.join(r['channel'] for r in self.radio['radioList'])}
  if c.module=='radio':
   if c.action=='set':
    self.writes+=1
    if self.reject:raise ValueError('rejected')
    self.radio=copy.deepcopy(c.data)
   return copy.deepcopy(self.radio)
  if c.module=='country_channel':return {'channel_2G':[{'channel':1},{'channel':13}],'channel_5G':[{'channel':52},{'channel':64}]}
  raise AssertionError(c)

class Contracts(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.tmp.name)/'db');self.key=DeviceKey('t','p','A');self.other=DeviceKey('t','q','B')
  for key in (self.key,self.other):self.store.add_project(key.tenant,key.project,key.project);self.store.bind_device(key.tenant,key.project,key.sn,{})
  self.registry=DeviceRegistry(self.store);self.driver=Driver();self.registry.register(self.key,self.driver);self.reports=GameReports(self.store,self.registry)
 def tearDown(self):self.registry.close();self.tmp.cleanup()
 def test_completed_scan_time_is_panel_time_not_optimize_time_and_is_scoped(self):
  stamp=int((time.time()-3600)*1000)
  self.store.save('t','p','A',RESULT,{'scanTime':stamp})
  self.store.save('t','p','A','hub.channel.optimize.5G',{'optimizedAt':int(time.time()*1000),'afterChannel':'64'})
  value=signal_tools.current(self.registry,self.key)
  self.assertEqual(datetime.fromtimestamp(stamp/1000,CHINA).strftime('%Y-%m-%d %H:%M:%S'),value['lastScanTime'])
  self.assertNotEqual(value['lastScanTime'],value['lastOptimizeChannelTime5'])
  self.assertIsNone(signal_tools.last_scan_time(self.registry,self.other));self.assertEqual(0,self.driver.writes)
 def test_all_optimization_entries_publish_same_real_event_and_duplicate_does_not_count(self):
  rows=[{'channel':64,'intfc':10},{'channel':52,'intfc':1}]
  stamp=time.time()-60
  for index,trigger in enumerate(('channel-optimize','ai-noise','game-environment')):
   self.driver.radio['radioList'][1]['channel']='64'
   with patch.object(signal_tools,'measurement',return_value=rows),patch.object(signal_tools.time,'time',return_value=stamp+index):result=signal_tools.optimize(self.registry,self.key,'5G',trigger=trigger)
   record_channel_action(self.store,self.key,'5G',result,trigger='retry')
  logs=self.reports.logs(self.key)['gameLogDetailList']
  self.assertEqual(3,len(logs));self.assertTrue(all('64' in x['eventDetail'] and '52' in x['eventDetail'] for x in logs))
  self.assertEqual(3,self.reports.acceleration_statistics(self.key)['antiBother']['todayCnt'])
  self.assertEqual([],self.reports.logs(self.other)['gameLogDetailList'])
 def test_rejected_or_unchanged_channel_does_not_create_action(self):
  self.driver.reject=True
  with patch.object(signal_tools,'measurement',return_value=[{'channel':64,'intfc':10},{'channel':52,'intfc':1}]):
   with self.assertRaises(ValueError):signal_tools.optimize(self.registry,self.key,'5G')
  record_channel_action(self.store,self.key,'5G',{'beforeChannel':52,'afterChannel':'52','optimizedAt':int(time.time()*1000)})
  self.assertEqual([],self.reports.logs(self.key)['gameLogDetailList'])
  self.assertEqual(1,self.reports.acceleration_statistics(self.key)['antiBother']['todayCnt'])
  record_channel_action(self.store,self.key,'5G',{'beforeChannel':52,'afterChannel':'52','optimizedAt':int(time.time()*1000)+1},trigger='automatic-ai-noise')
  self.assertEqual(1,self.reports.acceleration_statistics(self.key)['antiBother']['todayCnt'])
 def test_one_click_api_counts_completed_optimization_even_when_channel_stays(self):
  self.store.health('t','p','A',True)
  client=create_app(self.store,self.registry,{token_digest('qa'):Principal('t','Owner',True)}).test_client()
  headers={'Authorization':'Bearer qa'};body={'projectId':'p','sn':'A'}
  def post(path):
   value=client.post('/api/v1/'+path,json=body,headers=headers).get_json()
   self.assertEqual(0,value['code'],value);return value['data']
  with patch.object(signal_tools,'measurement',return_value=[{'channel':64,'intfc':10},{'channel':52,'intfc':1}]):
   switched=post('game/accelerate/oneStepAiNoiseReduction')
   self.assertEqual(('64','52'),(switched['beforeChannel'],switched['afterChannel']))
   kept=post('game/accelerate/oneStepAiNoiseReduction')
   self.assertEqual(kept['beforeChannel'],kept['afterChannel'])
  counts=post('game/accelerate/getAccelerateStatistics')['antiBother']
  self.assertEqual((2,2),(counts['todayCnt'],counts['historyCnt']))
  logs=post('game/accelerate/getGameLog')['gameLogDetailList']
  self.assertEqual(1,len(logs));self.assertIn('64切换到52',logs[0]['eventDetail'])

if __name__=='__main__':unittest.main()
