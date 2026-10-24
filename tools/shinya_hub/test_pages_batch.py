"""Ownership, expiry and image limits for the combined page batch."""
import base64,sys,tempfile,time,unittest
from pathlib import Path
import requests
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.macc_channels import MaccChannels,MaccError
from shinya_hub.devices import DeviceKey,DeviceRegistry
from shinya_hub.store import ProjectStore
from shinya_hub.api import Principal,create_app,token_digest
from shinya_hub.floorplan import validate

NOW=1791600000
TUNNEL={'id':3,'targetSn':'A','remoteHost':'192.168.1.8','remotePort':3389,
 'tunnelStatus':'ESTABLISHED','localHost':'47.104.22.0','localPort':64000,
 'finishTime':NOW*1000,'vaildTime':3}
class Session:
 def __init__(self,wrong=False,timeout=False):self.calls=[];self.cookies=requests.cookies.RequestsCookieJar();self.wrong=wrong;self.timeout=timeout;self.closed=False
 def post(self,url,**kw):
  b=kw['json'];self.calls.append(b)
  v={'code':0,'data':{'serialNumber':'other' if self.wrong else 'A','groupId':1,'onlineStatus':'ON'}} if b['api'].startswith('/maint/') else {'code':22008,'data':dict(TUNNEL)}
  if self.timeout and b['method']=='POST':raise requests.Timeout()
  return type('Response',(),{'status_code':200,'content':b'{}','json':lambda _:v})()
 def close(self):self.closed=True
class MaccTests(unittest.TestCase):
 def setUp(self):self.key=DeviceKey('a','home','A');self.s=Session();self.m=MaccChannels(lambda:self.s,lambda:NOW+12,lambda _:None);self.row={'officialProjectId':1,'account':'owner','maccAccount':'owner','maccEnabled':True,'maccCookies':[]}
 def test_actual_expiry_and_existing_channel_are_preserved(self):
  r=self.m.rdp(self.row,self.key,'192.168.1.8');self.assertEqual(r['data']['validSeconds'],10788);self.assertEqual(r['data']['endTime'],(NOW+10800)*1000)
  self.assertEqual(len(self.s.calls),2);self.assertFalse(any('destroy' in c['api'] for c in self.s.calls));self.assertTrue(self.s.closed)
 def test_verify_prevents_cross_project_write(self):
  self.s.wrong=True
  with self.assertRaises(MaccError):self.m.rdp(self.row,self.key,'192.168.1.8')
  self.assertEqual(len(self.s.calls),1)
 def test_timed_out_allocation_is_not_retried(self):
  self.s.timeout=True
  with self.assertRaises(MaccError):self.m.rdp(self.row,self.key,'192.168.1.8')
  self.assertEqual(len(self.s.calls),2)
 def test_foreign_target_and_expired_tunnel_are_rejected(self):
  for changes in ({'targetSn':'B'},{'remoteHost':'192.168.2.8'},{'remotePort':22},{'finishTime':(NOW-20000)*1000},{'vaildTime':float('nan')}):
   with self.assertRaises(MaccError):self.m.result({**TUNNEL,**changes},self.key,'192.168.1.8')

class FloorTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.tmp.name)/'hub.sqlite');self.registry=DeviceRegistry(self.store)
  for tenant,project,sn in [('a','home','A'),('a','office','B'),('b','home','C')]:self.store.add_project(tenant,project,project);self.store.bind_device(tenant,project,sn,{'isMain':True})
  self.app=create_app(self.store,self.registry,{token_digest('owner'):Principal('a','a',True),token_digest('reader'):Principal('a','r',False),token_digest('foreign'):Principal('b','b',True)})
  self.client=self.app.test_client()
  # SOF dimensions; this fixture tests the bounded JPEG container validation.
  self.image={'mime':'image/jpeg','image':base64.b64encode(b'\xff\xd8\xff\xc0\x00\x08\x08\x00\x10\x00\x20\x01\xff\xd9').decode(),'width':32,'height':16}
 def tearDown(self):self.app.extensions['shinya_diagnosis'].close();self.registry.close();self.tmp.cleanup()
 def call(self,body,token='owner',path='hub/floorplan'):
  return self.client.post('/api/v1/'+path,json=body,headers={'Authorization':'Bearer '+token}).get_json()
 def test_save_read_permissions_and_project_isolation(self):
  b={'projectId':'home','apSn':'A','action':'save',**self.image}
  self.assertEqual(self.call(b)['code'],0)
  self.assertEqual(self.call(b,'reader')['code'],403)
  self.assertEqual(self.call({**b,'projectId':'office'})['code'],404)
  self.assertEqual(self.call({**b,'action':'read'},'foreign')['code'],404)
  r=self.call({**b,'action':'read'},'reader');self.assertEqual(r['data']['image'],self.image['image'])
 def test_only_image_route_accepts_larger_body(self):
  b={'projectId':'home','apSn':'A','action':'save',**self.image,'padding':'a'*70000}
  self.assertEqual(self.call(b)['code'],0)
  self.assertEqual(self.client.post('/api/v1/hub/diagnosis',json=b,headers={'Authorization':'Bearer owner'}).status_code,413)
 def test_invalid_image_and_dimensions(self):
  for v in ({'mime':'image/png'},{'width':33},{'image':'bad!'},{'image':'a'*1048577}):
   with self.assertRaises(ValueError):validate({**self.image,**v})
if __name__=='__main__':unittest.main()
