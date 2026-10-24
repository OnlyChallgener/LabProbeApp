import tempfile
import unittest
from pathlib import Path
from shinya_hub.api import Principal, create_app, token_digest
from shinya_hub.devices import DeviceKey, DeviceRegistry
from shinya_hub.store import ProjectStore
from shinya_hub.stun_bridge import StunBridge

class FakeBridge:
    def __init__(self): self.calls=[]
    def request(self,key,operation,rule_id,draft):
        self.calls.append((key,operation,rule_id,draft))
        return {'ok':True,'rules':[]}

class StunRouteTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.store=ProjectStore(Path(self.temp.name)/'hub.sqlite')
        for tenant,project,sn in [('owner','home','A'),('owner','office','B'),('other','home','C')]:
            self.store.add_project(tenant,project,project)
            self.store.bind_device(tenant,project,sn,{'name':sn})
        self.registry=DeviceRegistry(self.store)
        self.bridge=FakeBridge()
        self.client=create_app(self.store,self.registry,{
            token_digest('owner-token'):Principal('owner','Owner',True),
            token_digest('read-token'):Principal('owner','Read',False),
        },stun_bridge=self.bridge).test_client()
    def tearDown(self): self.registry.close();self.temp.cleanup()
    def post(self,body,token='owner-token'):
        return self.client.post('/api/v1/hub/stun',json=body,
               headers={'Authorization':'Bearer '+token}).get_json()
    def test_switch_routes_to_exact_owned_device(self):
        for project,sn in [('home','A'),('office','B')]:
            self.assertEqual(self.post({'projectId':project,'apSn':sn,'operation':'list'})['code'],0)
            self.assertEqual(self.bridge.calls[-1][0],DeviceKey('owner',project,sn))
    def test_another_tenant_or_wrong_project_cannot_reach_backend(self):
        for project,sn in [('home','C'),('home','B'),('unknown','A')]:
            self.assertNotEqual(self.post({'tenant':'other','projectId':project,'apSn':sn,'operation':'delete','ruleId':'rule'})['code'],0)
        self.assertEqual(self.bridge.calls,[])
    def test_read_only_account_cannot_write(self):
        body={'projectId':'home','apSn':'A','operation':'create','draft':{}}
        self.assertEqual(self.post(body,'read-token')['code'],403)
        self.assertEqual(self.bridge.calls,[])
    def test_rule_path_cannot_be_used_as_proxy_url(self):
        bridge=StunBridge([{'tenant':'owner','project':'home','sn':'A','port':58443,'token':'internal'}])
        with self.assertRaises(ValueError):
            bridge.request(DeviceKey('owner','home','A'),'delete','../../router/password')
        with self.assertRaises(LookupError):
            bridge.request(DeviceKey('other','home','A'),'list')

if __name__=='__main__':unittest.main()
