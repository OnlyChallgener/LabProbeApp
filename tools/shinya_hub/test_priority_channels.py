"""Read-only regression checks for the first channel batch."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.official_channels import OfficialChannels, OfficialChannelError
from shinya_hub.devices import DeviceKey, DeviceRegistry
from shinya_hub.store import ProjectStore
from shinya_hub.api import Principal, create_app, token_digest

class Response:
    def __init__(self,data,status=200):
        self.value=data;self.status_code=status;self.content=b'{}'
    def json(self):return self.value

class Session:
    def __init__(self):self.calls=[];self.closed=False;self.expired=False;self.wrong=False
    def post(self,url,**kwargs):
        self.calls.append((url,kwargs))
        if url.endswith('accountPasswordLogin'):
            return Response({'code':0,'data':{'token':'renewed-official'}})
        if url.endswith('getDeviceInfoList2'):
            if self.expired and kwargs['headers']['Authorization']!='Bearer renewed-official':
                return Response({},401)
            return Response({'code':0,'data':{'apList':[{'sn':'foreign' if self.wrong else 'A1'}]}})
        return Response({'code':0,'data':{'port':12345},'msg':''})
    def close(self):self.closed=True

class ChannelTests(unittest.TestCase):
    def setUp(self):
        self.session=Session();self.key=DeviceKey('alice','home','A1')
        self.adapter=OfficialChannels([{'tenant':'alice','project':'home','sn':'A1',
            'officialProjectId':123,'token':'official-only','account':'authorized','password':'test-secret'}],lambda:self.session)
    def test_project_mapping_and_only_official_credential(self):
        for path,ip in [('homeAp/getTunnelUrl',None),('remoteAccess/checkStaRdp','192.168.1.8'),
                        ('remoteAccess/getRdpTunnel','192.168.1.8')]:
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
    def test_expired_session_renews_before_write_and_reverifies_sn(self):
        self.session.expired=True
        self.assertEqual(self.adapter.request(self.key,'remoteAccess/getRdpTunnel','192.168.1.8')['code'],0)
        self.assertEqual([x[0].rsplit('/',1)[1] for x in self.session.calls],
            ['getDeviceInfoList2','accountPasswordLogin','getDeviceInfoList2','getRdpTunnel'])

class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.tmp.name)/'test.sqlite')
        for tenant,project,sn in [('alice','home','A1'),('alice','office','A2'),('bob','home','B1')]:
            self.store.add_project(tenant,project,project)
            self.store.bind_device(tenant,project,sn,{'name':sn,'isMain':True})
        self.registry=DeviceRegistry(self.store)
        self.app=create_app(self.store,self.registry,{
            token_digest('alice'):Principal('alice','Alice',True),
            token_digest('bob'):Principal('bob','Bob',False)})
        self.client=self.app.test_client()
    def tearDown(self):
        self.app.extensions['shinya_diagnosis'].close();self.registry.close();self.tmp.cleanup()
    def get(self,path,account='alice',body=None):
        return self.client.get(path,query_string=body or {},headers={'Authorization':'Bearer '+account}).get_json()
    def test_guarded_macs_are_deduplicated_and_project_scoped(self):
        self.store.save('alice','home','A1','hub.children.members',
            {'data':{'rows':[{'staId':'uid','mergeMacs':['aa:11:22:33:44:55','AA1122334455']}]},'source':'native'})
        path='/homewlan/childProtection/getStaListWithMainMac/home'
        self.assertEqual(self.get(path)['data'],['AA1122334455'])
        self.assertEqual(self.get(path,body={'projectId':'office'})['code'],403)
        self.assertEqual(self.get(path,'bob')['code'],503)
    def test_tunnel_api_rejects_viewer_and_foreign_device(self):
        for route in ('homeAp/getTunnelUrl','remoteAccess/checkStaRdp','remoteAccess/getRdpTunnel'):
            path='/homewlan/'+route
            foreign=self.client.post(path,json={'projectId':'office','sn':'A1'},headers={'Authorization':'Bearer alice'}).get_json()
            viewer=self.client.post(path,json={'projectId':'home','sn':'B1'},headers={'Authorization':'Bearer bob'}).get_json()
            self.assertEqual(foreign['code'],404);self.assertEqual(viewer['code'],403)

if __name__=='__main__':unittest.main()
