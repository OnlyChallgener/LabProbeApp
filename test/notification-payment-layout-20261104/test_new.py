import unittest
import test_existing
from shinya_hub.notification_preferences import configuration
class Extra(test_existing.Tests):
 def test_app_scenarios_and_dedupe(self):
  from datetime import datetime
  from shinya_hub.daily import CHINA
  self.now=datetime(2026,11,4,22,0,tzinfo=CHINA).timestamp()
  rule=self.rule(social=True);rule['settings']=configuration(updates={'social':{'durationEnabled':False,'nightEnabled':True,'longEnabled':True,'longMinutes':3,'frequencyEnabled':True,'frequencyCount':2}})
  self.detect(rule,self.report('social',self.now-60,self.now),self.now)
  events=[]
  for i in range(1,5):events+=self.detect(rule,self.report('social',self.now,self.now+i*60),self.now+i*60)
  kinds=[e['ruleKind'] for e in events];self.assertEqual(kinds.count('appNight'),1);self.assertEqual(kinds.count('appLong'),1)
  for i in range(5,16):self.detect(rule,{'basis':'minutes','apps':[]},self.now+i*60)
  more=[]
  for i in range(16,19):more+=self.detect(rule,self.report('social',self.now+15*60,self.now+i*60),self.now+i*60)
  self.assertEqual(sum(e['ruleKind']=='frequency' for e in more),1)
 def test_all_three_categories_new_thresholds(self):
  for category in ['game','video','social']:
   rule=self.rule(**{category:True});rule['settings']=configuration(updates={category:{'durationEnabled':False,'longEnabled':True,'longMinutes':2}})
   self.detect(rule,self.report(category,self.now-60,self.now),self.now)
   events=[]
   for i in range(1,4):events+=self.detect(rule,self.report(category,self.now,self.now+i*60),self.now+i*60)
   self.assertEqual(sum(e['ruleKind']=='appLong' and e['category']==category for e in events),1,category)
   self.now+=600
 def test_new_scene_service_save_readback(self):
  import threading
  from unittest.mock import patch
  from shinya_hub.xiaoxing import XiaoxingService
  service=XiaoxingService.__new__(XiaoxingService)
  service.children=None;service.store=self.store;service._lock=threading.RLock()
  state={}
  service._load=lambda key,name,default:state.get(name,default)
  service._save=lambda key,name,value:state.update({name:value})
  service.get_notify_rule=lambda *args:next(value for value in state.values() if isinstance(value,dict) and 'aa:bb:cc:dd:ee:ff' in value)['aa:bb:cc:dd:ee:ff']
  station={'mac':'aa:bb:cc:dd:ee:ff','type':'phone','remark':'phone'}
  with patch('shinya_hub.xiaoxing.station_snapshots',return_value=[station]):
   for category in ['game','video','social']:
    settings={'nightEnabled':True,'longEnabled':True,'longMinutes':47,'frequencyEnabled':True,'frequencyCount':7,'durationEnabled':False}
    saved=service.set_notify_rule(self.key,{},dict(mac=station['mac'],**{category:True},settings={category:settings}))
    for field,value in settings.items():self.assertEqual(saved['settings'][category][field],value)
   saved=service.set_notify_rule(self.key,{},dict(mac=station['mac'],social=False))
  self.assertTrue(saved['game']);self.assertTrue(saved['video']);self.assertFalse(saved['social'])
  self.assertEqual(saved['settings']['social']['frequencyCount'],7)
 def test_payment_service_save_and_readback(self):
  import threading
  from unittest.mock import patch
  from shinya_hub.xiaoxing import XiaoxingService
  service=XiaoxingService.__new__(XiaoxingService);service.children=None;service.store=self.store;service._lock=threading.RLock();state={}
  service._load=lambda key,name,default:state.get(name,default)
  service._save=lambda key,name,value:state.update({name:value})
  service.get_notify_rule=lambda *args:next(value for value in state.values() if isinstance(value,dict) and 'aa:bb:cc:dd:ee:ff' in value)['aa:bb:cc:dd:ee:ff']
  station={'mac':'aa:bb:cc:dd:ee:ff','type':'phone','remark':'phone'}
  fields={'accessEnabled':False,'frequencyEnabled':True,'frequencyCount':3,'shoppingDurationEnabled':True,'shoppingMinutes':17,'shoppingFrequencyEnabled':True,'shoppingFrequencyCount':2}
  with patch('shinya_hub.xiaoxing.station_snapshots',return_value=[station]):
   saved=service.set_notify_rule(self.key,{},dict(mac=station['mac'],payment=True,settings={'payment':fields}))
   for name,value in fields.items():self.assertEqual(saved['settings']['payment'][name],value)
   disabled=service.set_notify_rule(self.key,{},dict(mac=station['mac'],payment=False))
  self.assertFalse(disabled['payment']);self.assertEqual(disabled['settings']['payment']['shoppingMinutes'],17)
 def test_catalog_ratings_and_payment_scope(self):
  from shinya_hub.catalog import AppCatalog
  from shinya_hub.notification_preferences import validate_apps
  catalog=AppCatalog();rows={r['id']:r for r in catalog.official+catalog.extra}
  for key in ['9-208-1-0','9-209-1-0','10-5-2-0','9-201-1-0','9-202-1-0','9-203-1-0','9-220-1-0','9-206-1-0','9-232-1-0','9-233-1-0']:
   self.assertEqual(rows[key]['ageRating'],'17+')
  for key in ['9-219-1-0','9-221-1-0','9-222-1-0']:self.assertEqual(rows[key]['ageRating'],'9+')
  payment=[i for i,r in rows.items() if r.get('type')=='0006']
  validate_apps(configuration(updates={'payment':{'allServices':False,'appIds':payment}}))
  self.assertIn('18-4-2-0',payment)
 def test_frequency_validation_and_preserved_settings(self):
  for value in [0,101,True,1.2]:
   with self.assertRaises(ValueError):configuration(updates={'social':{'frequencyCount':value}})
  c=configuration({'social':{'minutes':5}});self.assertEqual(c['social']['minutes'],5);self.assertFalse(c['social']['nightEnabled']);self.assertFalse(c['social']['longEnabled'])
class Commerce(Extra):
 def setUp(self):
  super().setUp()
  self.engine.by_id['shop']={'id':'shop','type':'0006','showName':'购物应用'}
 def test_commerce_thresholds_count_source_events_once(self):
  rule=self.rule(payment=True)
  rule['settings']=configuration(updates={'payment':{'accessEnabled':False,'frequencyEnabled':True,'frequencyCount':2,'shoppingDurationEnabled':True,'shoppingMinutes':2,'shoppingFrequencyEnabled':True,'shoppingFrequencyCount':2}})
  self.detect(rule,{},self.now)
  first=self.report('18-1-1-0',self.now+60,self.now+120)
  self.assertFalse(self.detect(rule,first,self.now+120))
  self.assertFalse(self.detect(rule,first,self.now+180))
  second=self.report('18-1-1-0',self.now+240,self.now+300)
  events=self.detect(rule,second,self.now+300)
  self.assertEqual([e['ruleKind'] for e in events],['paymentFrequency'])
  self.assertFalse(self.detect(rule,second,self.now+360))
  shop=self.report('shop',self.now+420,self.now+540)
  events=self.detect(rule,shop,self.now+540)
  self.assertEqual([e['ruleKind'] for e in events],['shoppingDuration'])
  shop['apps'][0]['sessionRanges'][0].update(endEpoch=self.now+600,activeSeconds=180)
  self.assertFalse(self.detect(rule,shop,self.now+600))
  more=self.report('shop',self.now+1200,self.now+1260)
  events=self.detect(rule,more,self.now+1260)
  self.assertEqual([e['ruleKind'] for e in events],['shoppingFrequency'])
 def test_shopping_sparse_activity_uses_active_seconds_not_span(self):
  rule=self.rule(payment=True);rule['settings']=configuration(updates={'payment':{'shoppingDurationEnabled':True,'shoppingMinutes':5}})
  self.detect(rule,{},self.now)
  sparse=self.report('shop',self.now+60,self.now+1260)
  sparse['apps'][0]['sessionRanges'][0]['activeSeconds']=60
  self.assertFalse(self.detect(rule,sparse,self.now+1260))
 def test_payment_settings_partial_and_numeric_validation(self):
  old=configuration(updates={'payment':{'shoppingMinutes':17,'frequencyCount':3}})
  updated=configuration(old,{'payment':{'accessEnabled':False}})
  self.assertEqual(updated['payment']['shoppingMinutes'],17);self.assertEqual(updated['payment']['frequencyCount'],3)
  for key,value in [('shoppingMinutes',0),('shoppingMinutes',1441),('shoppingFrequencyCount',101),('frequencyCount',True)]:
   with self.assertRaises(ValueError):configuration(updates={'payment':{key:value}})
if __name__=='__main__':unittest.main(verbosity=2)
