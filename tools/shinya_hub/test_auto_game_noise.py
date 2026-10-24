"""Fresh game gating, interruption cancellation and persistent switch cooldown."""
import copy,sys,time,unittest
from unittest.mock import patch
sys.path.insert(0,'D:/Github/ShinyaHub')
from test_channel_log_contract import Contracts
from shinya_hub.game_noise import AutoNoiseReduction,has_active_game,TELEMETRY,STATE
from shinya_hub.game_acceleration import PREFERENCES
from shinya_hub import signal_tools

class AutomaticNoise(Contracts):
 def activity(self,now):return {'source':'firmware.rcgame','observedAt':now,'active':{'192.168.5.43':{'game_app_num':1,'study_app_num':0,'single':{'3-1-2-0':{'gameidx':'3-1-2-0','type':'game','time':now}}}}}
 def test_only_fresh_actual_game_activity_is_eligible(self):
  now=time.time();v=self.activity(now)
  self.assertTrue(has_active_game(v,now));self.assertFalse(has_active_game(v,now+46))
  for active in ([],{}, {'192.168.5.43':{'1-1-2-0':{}}},{'invalid':{'3-1-2-0':{}}}):
   self.assertFalse(has_active_game({**v,'active':active},now))
  for terminal in ({'game_app_num':0,'single':v['active']['192.168.5.43']['single']},
                   {'game_app_num':1,'single':{'7-4-1-0':{'type':'study','gameidx':'7-4-1-0'}}},
                   {'game_app_num':1,'single':{'3-0-0-0':{'type':'game','gameidx':'3-0-0-0'}}}):
   self.assertFalse(has_active_game({**v,'active':{'192.168.5.43':terminal}},now))
 def test_disabled_other_router_and_recent_manual_switch_do_not_run(self):
  now=time.time();noise=AutoNoiseReduction(self.registry)
  try:
   self.store.save('t','p','A',TELEMETRY,self.activity(now))
   self.assertFalse(noise.eligible(self.key,now))
   self.store.save('t','p','A',PREFERENCES,{'noiseReduction':1})
   self.assertTrue(noise.eligible(self.key,now));self.assertFalse(noise.eligible(self.other,now))
   self.store.save('t','p','A','hub.channel.optimize.5G',{'beforeChannel':'64','afterChannel':'52','optimizedAt':now*1000})
   self.assertFalse(noise.eligible(self.key,now+600))
  finally:noise.close()
 def test_auto_requires_improvement_and_rechecks_off_before_mutation(self):
  with patch.object(signal_tools,'measurement',return_value=[{'channel':64,'intfc':10},{'channel':52,'intfc':9}]):
   result=signal_tools.optimize(self.registry,self.key,'5G',minimum_improvement=.2)
  self.assertEqual('64',result['afterChannel']);self.assertEqual(0,self.driver.writes)
  with patch.object(signal_tools,'measurement',return_value=[{'channel':64,'intfc':10},{'channel':52,'intfc':1}]):
   with self.assertRaisesRegex(RuntimeError,'未切换'):signal_tools.optimize(self.registry,self.key,'5G',minimum_improvement=.2,permitted=lambda:False)
  self.assertEqual(0,self.driver.writes);self.assertEqual([],self.reports.logs(self.key)['gameLogDetailList'])
 def test_success_is_persisted_once_and_survives_controller_restart(self):
  now=time.time();self.store.save('t','p','A',PREFERENCES,{'noiseReduction':1});self.store.save('t','p','A',TELEMETRY,self.activity(now))
  noise=AutoNoiseReduction(self.registry)
  try:
   with patch('shinya_hub.game_noise.status',return_value={'isOpen':True,'noiseReduction':1}),patch.object(noise,'acceleration_enabled',return_value=True),patch.object(signal_tools,'measurement',return_value=[{'channel':64,'intfc':10},{'channel':52,'intfc':1}]):noise.run(self.key)
   self.assertEqual(1,self.driver.writes);self.assertEqual(1,len(self.reports.logs(self.key)['gameLogDetailList']))
   self.assertEqual('complete',self.store.cached('t','p','A',STATE)['value']['status'])
  finally:noise.close()
  again=AutoNoiseReduction(self.registry)
  try:self.assertFalse(again.eligible(self.key,now+15))
  finally:again.close()

if __name__=='__main__':unittest.main()
