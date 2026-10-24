import copy,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceKey,DeviceRegistry
from shinya_hub.station_controls import change_rate
from shinya_hub.store import ProjectStore

class Firmware:
 def __init__(self):
  self.rows=[{'comment':ip,'ipRange':ip+'-'+ip,'mode':'per_ip','intf':'br-wan','upRate':'1000','downRate':'1000','enable':'on','pri':str(i)} for i,ip in enumerate(['192.168.5.58','192.168.5.59'])]
  self.writes=[];self.ignore=False
 def execute(self,c):
  if c.action=='del':
   self.writes.append(c)
   if not self.ignore:self.rows=[r for r in self.rows if r['comment'] not in c.data['rulename']]
  return {'list':copy.deepcopy(self.rows)}

class Unlimited(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.tmp.name)/'db');self.key=DeviceKey('t','p','s')
  self.store.add_project('t','p','home');self.store.bind_device('t','p','s',{})
  self.registry=DeviceRegistry(self.store);self.driver=Firmware();self.registry.register(self.key,self.driver)
 def tearDown(self):self.registry.close();self.tmp.cleanup()
 def delete(self,name='192.168.5.58'):
  return change_rate(self.registry,self.key,UFCommand('dev_config','del','flowctrl_udp',{'configId':'current','configTime':'now','currentTime':'now','rulename':[name]}),{'192.168.5.58'})
 def test_original_app_payload_deletes_only_current_station_and_clears_cache(self):
  other=copy.deepcopy(self.driver.rows[1]);result=self.delete();self.assertTrue(result['verified']);self.assertEqual([other],self.driver.rows)
  cached=self.store.cached('t','p','s',self.registry.resource(UFCommand('dev_config','get','flowctrl_udp',None)))
  self.assertEqual([other],cached['value']['list'])
 def test_already_removed_is_idempotent_without_a_device_write(self):
  self.driver.rows.pop(0);self.assertTrue(self.delete()['verified']);self.assertFalse(self.driver.writes)
 def test_custom_rule_names_are_scoped_by_actual_target(self):
  self.driver.rows[0]['comment']='custom';self.assertTrue(self.delete('custom')['verified'])
 def test_foreign_target_or_ignored_delete_is_never_success(self):
  with self.assertRaises(PermissionError):self.delete('192.168.5.59')
  self.assertFalse(self.driver.writes);self.driver.ignore=True
  with self.assertRaises(ValueError):self.delete()

if __name__=='__main__':unittest.main()
