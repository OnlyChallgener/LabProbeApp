import sys,importlib.util,unittest,tempfile,json
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.official_channels import OfficialChannels
from shinya_hub.devices import DeviceKey
spec=importlib.util.spec_from_file_location('shinya_hub.official_account',Path('tools/shinya_hub/official_account_source.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Reply:
 status_code=200;content=b'bounded'
 def __init__(self,data):self.data=data
 def json(self):return {'code':0,'data':self.data}
class Session:
 def __init__(self):self.calls=[];self.sn='A'
 def post(self,url,**kw):
  self.calls.append((url,kw))
  return Reply({'token':'official-secret'} if 'accountPasswordLogin' in url else {'dataList':[{'projectId':42,'apList':[{'sn':self.sn}]}]})
 def close(self):pass
class Store:
 def owned_device(self,*key):return key in {('t','p','A'),('t','q','B')}
 def projects(self,tenant):return [{'projectId':'p','apList':[{'sn':'A'}]},{'projectId':'q','apList':[{'sn':'B'}]}]
class Tests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.s=Session();self.channels=OfficialChannels([{'tenant':'t','project':'p','sn':'A','officialProjectId':42,'token':'old'}],session_factory=lambda:self.s)
  self.service=module.OfficialAccount(self.channels,Store(),Path(self.tmp.name)/'official');self.key=DeviceKey('t','p','A')
 def tearDown(self):self.tmp.cleanup()
 def test_verified_binding_is_encrypted_and_restored(self):
  value=self.service.bind(self.key,'18617143092','sample-secret');self.assertTrue(value['verified']);self.assertNotIn('token',value)
  stored=self.service.vault.value_path.read_text();self.assertNotIn('sample-secret',stored);self.assertNotIn('18617143092',stored)
  restored=module.OfficialAccount(self.channels,Store(),Path(self.tmp.name)/'official');self.assertTrue(restored.status(self.key)['bound'])
  self.assertTrue(restored.status(DeviceKey('t','q','B'))['bound'])
  self.assertNotIn(('t','q','B'),self.channels.bindings)
 def test_mismatching_router_does_not_overwrite_existing_binding(self):
  self.s.sn='foreign'
  with self.assertRaises(ValueError):self.service.bind(self.key,'user','sample-secret')
  self.assertEqual('old',self.channels.bindings[('t','p','A')]['token']);self.assertFalse(self.service.vault.value_path.exists())
 def test_foreign_project_is_rejected_before_login(self):
  with self.assertRaises(PermissionError):self.service.bind(DeviceKey('t','foreign','A'),'user','sample-secret')
  self.assertFalse(self.s.calls)
 def test_one_login_matches_multiple_owned_projects(self):
  original=self.s.post
  def post(url,**kw):
   if 'getProjectList2' in url:
    self.s.calls.append((url,kw));return Reply({'dataList':[{'projectId':42,'apList':[{'sn':'A'}]},{'projectId':43,'apList':[{'sn':'B'},{'sn':'foreign'}]}]})
   return original(url,**kw)
  self.s.post=post
  value=self.service.bind(self.key,'user','sample-secret')
  self.assertEqual(2,value['projectCount'])
  self.assertEqual(43,self.channels.bindings[('t','q','B')]['officialProjectId'])
  self.assertEqual(1,sum('accountPasswordLogin' in v[0] for v in self.s.calls))
  self.assertEqual(2,len(self.channels.bindings))
if __name__=='__main__':unittest.main()
