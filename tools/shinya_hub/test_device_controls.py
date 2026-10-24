"""Contract checks for real state, project boundaries, and vendor relay identity."""
import copy,importlib.util,json,os,sys,tempfile,unittest
from pathlib import Path
from contextlib import contextmanager
sys.path.insert(0,'D:/Github/ShinyaHub')
STAGE=Path(os.environ.get('SHINYA_TEST_STAGE','test/_analysis/oct11-device-controls/deployment/shinya_hub'))
def module(name):
 spec=importlib.util.spec_from_file_location('shinya_hub.'+name,STAGE/(name+'.py'))
 value=importlib.util.module_from_spec(spec);sys.modules[spec.name]=value;spec.loader.exec_module(value);return value
controls=module('station_controls');macc=module('macc_channels');api=module('api')
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceKey
from shinya_hub.store import ProjectStore
class Driver:
 def __init__(self):
  self.band={'apselect_list':[{'mac':'02:00:00:00:00:02','sel_ap':'second','sel_band':'5G'}]}
  self.rate={'list':[{'comment':'192.168.5.43','ipRange':'192.168.5.43-192.168.5.43','mode':'per_ip','intf':'br-wan','pri':'149','upRate':'10000','downRate':'10000','enable':'on'}]}
  self.writes=[];self.ignore=False
 def execute(self,c):
  value=self.band if c.module=='apselect' else self.rate
  if c.action=='get':return copy.deepcopy(value)
  self.writes.append(c)
  if not self.ignore:
   if c.module=='apselect':self.band=copy.deepcopy(c.data)
   elif c.action=='update':self.rate={'list':copy.deepcopy(c.data['list'])}
   elif c.action=='add':self.rate['list'].extend(copy.deepcopy(c.data['list']))
   elif c.action=='del':self.rate={'list':[]}
  return {'code':0}
class Registry:
 def __init__(self,store):
  import threading
  self.store=store;self.driver=Driver();self._lock=threading.RLock();self._mutation_generation={}
 @contextmanager
 def device_operation(self,key):yield self.driver
 resource=staticmethod(controls.DeviceRegistry.resource)
class Contracts(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.tmp.name)/'store.sqlite')
  self.store.add_project('t','p','p');self.store.bind_device('t','p','be72',{'isMain':True})
  self.key=DeviceKey('t','p','be72');self.r=Registry(self.store)
 def tearDown(self):self.tmp.cleanup()
 def test_only5g_preserves_other_station_and_router_lock(self):
  controls.change_only5g(self.r,self.key,'02:00:00:00:00:01',True)
  self.assertEqual(2,len(self.r.driver.band['apselect_list']))
  controls.change_only5g(self.r,self.key,'02:00:00:00:00:02',False)
  self.assertEqual('second',self.r.driver.band['apselect_list'][0]['sel_ap'])
  self.assertEqual('auto',self.r.driver.band['apselect_list'][0]['sel_band'])
 def test_unconfirmed_only5g_is_error(self):
  self.r.driver.ignore=True
  with self.assertRaises(ValueError):controls.change_only5g(self.r,self.key,'02:00:00:00:00:01',True)
 def test_rate_update_and_foreign_project_rejection(self):
  row={**self.r.driver.rate['list'][0],'upRate':'1000','downRate':'1000'}
  c=UFCommand('dev_config','update','flowctrl_udp',{'list':[row]})
  with self.assertRaises(PermissionError):controls.change_rate(self.r,self.key,c,{'100.66.1.15'})
  self.assertFalse(self.r.driver.writes)
  controls.change_rate(self.r,self.key,c,{'192.168.5.43'})
  self.assertEqual('1000',self.r.driver.rate['list'][0]['upRate'])
  self.r.driver.ignore=True;row['upRate']='2500'
  with self.assertRaises(ValueError):controls.change_rate(self.r,self.key,c,{'192.168.5.43'})
 def test_relay_remote_client_and_conflicting_sn(self):
  value=json.loads(Path('test/_analysis/oct11-device-controls/rdp-macc-create.json').read_text())
  value=value.get('data',value)
  key=DeviceKey('t','p','G1TD8RX039025')
  channel=macc.MaccChannels(clock=lambda:value['finishTime']/1000+1)
  self.assertEqual(value['localPort'],channel.result(value,key,'192.168.5.201')['data']['port'])
  value['targetSn']='BE50'
  with self.assertRaises(macc.MaccError):channel.result(value,key,'192.168.5.201')
 def test_project_list_counts_for_both_projects(self):
  from shinya_hub.telemetry import USER_LIST
  from shinya_hub.devices import DeviceRegistry
  for p,sn in (('p','be72'),('q','be50')):
   self.store.add_project('t',p,p);self.store.bind_device('t',p,sn,{'isMain':True,'online':True})
   self.store.health('t',p,sn,True)
   row={'mac':'02:00:00:00:00:01','userIp':'192.168.5.43','connectType':'wireless','band':'5G'}
   self.store.merge_stations('t',p,sn,[row]);self.store.save('t',p,sn,DeviceRegistry.resource(USER_LIST),{'list':[row]})
  app=api.create_app(self.store,None,{api.token_digest('test'):api.Principal('t','test')})
  result=app.test_client().post('/homewlan/apMonitor/getProjectList2',headers={'Authorization':'Bearer test'},json={}).get_json()
  self.assertEqual(0,result['code'])
  self.assertEqual([(1,1),(1,1)],[(p['totalCount'],p['onlineStaCount']) for p in result['data']['dataList']])
if __name__=='__main__':unittest.main()
