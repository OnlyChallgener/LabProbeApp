import sys,copy,tempfile,unittest,json
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub import uu_acceleration as uu
from shinya_hub.commands import parse_command
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceRegistry,DeviceKey
from shinya_hub.api import create_app,Principal,token_digest
class Driver:
 family='be72'
 def __init__(self):self.value={'enable':'false','appver':'v7.13.20','memlimit':64,'stat':0,'acc':[]};self.writes=[];self.ignore=False
 def execute(self,c):
  if c.action=='get':return copy.deepcopy(self.value)
  self.writes.append(c.data)
  if not self.ignore:self.value['enable']='true' if c.data['enable'] else 'false'
  return None
class UU(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.s=ProjectStore(Path(self.tmp.name)/'db');self.s.add_project('t','p','home');self.s.bind_device('t','p','s',{})
  self.r=DeviceRegistry(self.s);self.k=DeviceKey('t','p','s');self.d=Driver();self.r.register(self.k,self.d)
 def tearDown(self):self.r.close();self.tmp.cleanup()
 def test_legacy_switch_is_native_boolean_and_preserves_memory_limit(self):
  c=parse_command('dev_sta set -m uu_acc \'{"option":"onoff","enable":"on"}\'')
  self.assertTrue(uu.change(self.r,self.k,c.data)['verified']);self.assertEqual([{'enable':True}],self.d.writes);self.assertEqual(64,self.d.value['memlimit'])
 def test_be50_and_unconfirmed_state_are_not_success(self):
  self.d.family='be50'
  with self.assertRaises(ValueError):uu.change(self.r,self.k,{'option':'onoff','enable':'on'})
  self.assertFalse(self.d.writes);self.d.family='be72';self.d.ignore=True
  with self.assertRaises(ValueError):uu.change(self.r,self.k,{'option':'onoff','enable':'on'})
 def test_permissions_and_foreign_scope_apply_before_native_control(self):
  a=create_app(self.s,self.r,{token_digest('r'):Principal('t','read'),token_digest('w'):Principal('t','write',True)}).test_client()
  b={'projectId':'p','sn':'s','type':1,'cmd':'dev_sta set -m uu_acc \'{"option":"onoff","enable":"on"}\''}
  self.assertEqual(403,a.post('/api/v1/api/device/command/sendCmd',headers={'Authorization':'Bearer r'},json=b).get_json()['code'])
  self.assertEqual(404,a.post('/api/v1/api/device/command/sendCmd',headers={'Authorization':'Bearer w'},json={**b,'sn':'foreign'}).get_json()['code'])
  self.assertFalse(self.d.writes)
 def test_unverified_install_parameters_are_rejected(self):
  for data in ({'option':'install','enable':'on'},{'enable':True},{'option':'onoff','enable':'on','memlimit':1}):
   with self.assertRaises(ValueError):parse_command('dev_sta set -m uu_acc \''+json.dumps(data)+'\'')
if __name__=='__main__':unittest.main()
