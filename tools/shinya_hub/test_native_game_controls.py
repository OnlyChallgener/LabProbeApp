import copy,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub import game_acceleration as game
from shinya_hub.commands import parse_command
from shinya_hub.devices import DeviceRegistry,DeviceKey
from shinya_hub.store import ProjectStore

class Firmware:
 def __init__(self):
  self.start={'Enable':'1','Cts2SelfEnable':'0',**game.LOW_RATE_PROFILES[1]}
  self.reject=False;self.writes=[]
 def execute(self,c):
  if c.module=='lowRate':
   if c.action=='update':
    self.writes.append(c.data)
    if not self.reject:
     self.start.update({'Enable':c.data['Enable']} if 'Enable' in c.data else c.data['Start'])
   return {'Start':copy.deepcopy(self.start)}
  if c.module=='rcgame':return {'enabled':'true','game':'on','air_flowctrl':'0'}
  if c.module=='flowctrl_qos':return {'list':[{'comment':'games','enable':'off','tc_pri':'2'}]}
  if c.module=='flowctrl':return {'reserve_switch':'on','list':[{'ifname':'br-wan','downloadBand':'1120'}]}
  if c.module=='shinya_game':
   if c.data=={'action':'status'}:
    from shinya_hub.commands import UFCommand
    return {'rcgame':self.execute(UFCommand('dev_sta','get','rcgame',None)),
      'flowctrl':self.execute(UFCommand('dev_config','get','flowctrl',None)),
      'qos':self.execute(UFCommand('dev_config','get','flowctrl_qos',None)),
      'lowRate':self.execute(UFCommand('ac_config','get','lowRate',None)),'policy':{}}
   return {}
  raise AssertionError(c)

class Controls(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.tmp.name)/'db')
  self.key=DeviceKey('tenant','project','serial');self.store.add_project('tenant','project','router')
  self.store.bind_device('tenant','project','serial',{})
  self.registry=DeviceRegistry(self.store);self.driver=Firmware();self.registry.register(self.key,self.driver)
 def tearDown(self):self.registry.close();self.tmp.cleanup()
 def test_priority_is_not_total_acceleration_and_flow_level_is_not_reservation(self):
  data=game.status(self.registry,self.key)
  self.assertEqual(1,data['openStatus']);self.assertEqual(0,data['gamePriority'])
  self.assertIsNone(data['flowCtrlMode']);self.assertEqual(1,data['lowRateMode'])
  for i,(threshold,rates) in enumerate(game.FLOW_RATE_PROFILES):
   policy={'whole_air_rate_threshold':threshold,'air_group':[{'group_name':'air_group_'+str(n),'limitrate':r} for n,r in enumerate(rates,1)]}
   self.assertEqual(i,game.flow_mode(policy))
   policy['air_group'][0]['limitrate']=99;self.assertIsNone(game.flow_mode(policy))
 def test_mode_persists_and_another_project_cannot_write(self):
  game.change_guard(self.registry,self.key,'lowRateMode',0)
  self.assertEqual('4',self.driver.start['ModePriority']);self.assertEqual('1',self.driver.start['Enable'])
  with self.assertRaises(PermissionError):game.change_guard(self.registry,DeviceKey('tenant','other','serial'),'lowRateMode',1)
  self.assertEqual(1,len(self.driver.writes))
 def test_readback_mismatch_restores_and_is_not_success(self):
  self.driver.reject=True
  with self.assertRaises(Exception):game.change_guard(self.registry,self.key,'lowRateMode',0)
  self.assertEqual(game.LOW_RATE_PROFILES[1],{k:self.driver.start[k] for k in game.LOW_RATE_PROFILES[1]})
  self.assertEqual(2,len(self.driver.writes))
 def test_generic_update_and_unverified_fields_are_rejected(self):
  with self.assertRaises(ValueError):parse_command("ac_config update -m lowRate '{\"Enable\":\"1\"}'")
  with self.assertRaises(ValueError):self.registry.update_game_guard(self.key,{'Start':{'Cts2SelfEnable':'1','CycleTime':'10'}})
  with self.assertRaises(ValueError):game.change_guard(self.registry,self.key,'lowRateMode',2)
  self.assertFalse(self.driver.writes)
 def test_http_permissions_device_scope_and_mode_contract(self):
  from shinya_hub.api import create_app,Principal,token_digest
  app=create_app(self.store,self.registry,{token_digest('read'):Principal('tenant','reader',False),token_digest('write'):Principal('tenant','writer',True)})
  client=app.test_client();body={'projectId':'project','sn':'serial','lowRateMode':0}
  try:
   for leaf in ['openGamePriority','setFlowCtrlBand','setLowRateMode','openWeakSignalGuard']:
    v=client.post('/api/v1/eSportAccelerate/'+leaf,json=body,headers={'Authorization':'Bearer read'}).get_json()
    self.assertEqual(403,v['code'],leaf)
   v=client.post('/api/v1/eSportAccelerate/setLowRateMode',json={**body,'sn':'another'},headers={'Authorization':'Bearer write'}).get_json()
   self.assertEqual(404,v['code']);self.assertFalse(self.driver.writes)
   v=client.post('/api/v1/eSportAccelerate/setLowRateMode',json=body,headers={'Authorization':'Bearer write'}).get_json()
   self.assertEqual(0,v['code']);self.assertEqual(0,v['data']['lowRateMode'])
  finally:
   for value in app.extensions.values():
    if hasattr(value,'close'):value.close()

if __name__=='__main__':unittest.main()
