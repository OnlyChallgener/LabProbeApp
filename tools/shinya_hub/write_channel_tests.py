from pathlib import Path

ROOT=Path('D:/Github/ShinyaHub')
(ROOT/'tests/test_official_channels.py').write_text(r'''import unittest
from shinya_hub.official_channels import OfficialChannels, OfficialChannelError
from shinya_hub.devices import DeviceKey

class Response:
 def __init__(self,data,status=200):self.value=data;self.status_code=status;self.content=b'{}'
 def json(self):return self.value

class Session:
 def __init__(self):self.calls=[];self.closed=False;self.expired=False;self.wrong=False
 def post(self,url,**kwargs):
  self.calls.append((url,kwargs))
  if url.endswith('accountPasswordLogin'):return Response({'code':0,'data':{'token':'renewed-official'}})
  if url.endswith('getDeviceInfoList2'):
   if self.expired and kwargs['headers']['Authorization']!='Bearer renewed-official':return Response({},401)
   return Response({'code':0,'data':{'apList':[{'sn':'foreign' if self.wrong else 'A1'}]}})
  return Response({'code':0,'data':{'port':12345},'msg':''})
 def close(self):self.closed=True

class ChannelTests(unittest.TestCase):
 def setUp(self):
  self.session=Session();self.key=DeviceKey('alice','home','A1')
  self.adapter=OfficialChannels([{'tenant':'alice','project':'home','sn':'A1',
   'officialProjectId':123,'token':'official-only','account':'authorized','password':'test-secret'}],lambda:self.session)
 def test_project_mapping_and_only_official_credential(self):
  for path,ip in [('homeAp/getTunnelUrl',None),('remoteAccess/checkStaRdp','192.168.1.8'),('remoteAccess/getRdpTunnel','192.168.1.8')]:
   self.assertEqual(self.adapter.request(self.key,path,ip)['code'],0)
   body=self.session.calls[-1][1]['json'];self.assertEqual(body['projectId'],123)
   self.assertNotIn('password',body)
   self.assertEqual(self.session.calls[-1][1]['headers']['Authorization'],'Bearer official-only')
   self.assertFalse(self.session.calls[-1][1]['allow_redirects'])
  self.assertTrue(self.session.closed)
 def test_owned_binding_and_live_sn_checked_before_allocation(self):
  for key in (DeviceKey('bob','home','A1'),DeviceKey('alice','office','A1'),DeviceKey('alice','home','A2')):
   with self.assertRaises(OfficialChannelError):self.adapter.request(key,'homeAp/getTunnelUrl')
  self.assertEqual(self.session.calls,[])
  self.session.wrong=True
  with self.assertRaises(OfficialChannelError):self.adapter.request(self.key,'homeAp/getTunnelUrl')
  self.assertEqual(len(self.session.calls),1)
 def test_expired_session_renews_only_before_write_and_reverifies_sn(self):
  self.session.expired=True
  self.assertEqual(self.adapter.request(self.key,'remoteAccess/getRdpTunnel','192.168.1.8')['code'],0)
  self.assertEqual([x[0].rsplit('/',1)[1] for x in self.session.calls],
   ['getDeviceInfoList2','accountPasswordLogin','getDeviceInfoList2','getRdpTunnel'])
''',encoding='utf8')
p=ROOT/'tests/test_api.py';s=p.read_text(encoding='utf8')
anchor='    def test_login_and_unsupported_routes_do_not_fake_success(self):'
test='''    def test_child_device_selection_returns_guarded_macs_only_in_owned_project(self):
        self.store.save('alice','home','A1','hub.children.members',
            {'data':{'rows':[{'staId':'uid','mergeMacs':['aa:11:22:33:44:55','AA1122334455']}]},'source':'native'})
        path='/homewlan/childProtection/getStaListWithMainMac/home'
        result=self.client.get(path,headers={'Authorization':'Bearer alice-token'}).get_json()
        self.assertEqual(result['code'],0)
        self.assertEqual(result['data'],['AA1122334455'])
        self.assertEqual(self.post(path,{'projectId':'office'})['code'],403)
        self.assertEqual(self.post(path,{},'bob-token')['code'],503)

    def test_tunnel_api_rejects_viewers_and_foreign_station_before_upstream(self):
        for route in ('homeAp/getTunnelUrl','remoteAccess/checkStaRdp','remoteAccess/getRdpTunnel'):
            path='/homewlan/'+route
            self.assertEqual(self.post(path,{'projectId':'home','sn':'A1'},'bob-token')['code'],403)
            self.assertEqual(self.post(path,{'projectId':'office','sn':'A1'})['code'],404)

'''
assert s.count(anchor)==1;s=s.replace(anchor,test+anchor);p.write_text(s,encoding='utf8')
print('Channel identity and child selection isolation tests added')
