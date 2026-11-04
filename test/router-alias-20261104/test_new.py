import tempfile
import unittest
from pathlib import Path
from shinya_hub.api import create_app, Principal, token_digest
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceRegistry


class AliasTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = ProjectStore(Path(self.tmp.name)/'test.sqlite')
        for owner, project, sn, mac in [('a','p','s1','001122334455'),('a','q','s2','001122334466'),('b','p','s3','001122334477')]:
            self.store.add_project(owner,project,project)
            self.store.bind_device(owner,project,sn,{'mac':mac,'name':sn,'isMain':True,'model':'BE72'})
        self.registry=DeviceRegistry(self.store)
        self.app=create_app(self.store,self.registry,{token_digest('a'):Principal('a','A',True),token_digest('b'):Principal('b','B',True),token_digest('ro'):Principal('a','ReadOnly',False)})
        self.client=self.app.test_client()

    def tearDown(self):
        self.registry.close()
        self.tmp.cleanup()

    def rename(self,payload,token='a',project='p'):
        return self.client.post('/homewlan/homeAp/setDeviceAlias',json={'data':payload},headers={'Authorization':'Bearer '+token,'projectId':project}).get_json()

    def rows(self,project='p'):
        return self.client.post('/homewlan/homeAp/getDeviceInfoList2',json={'projectId':project},headers={'Authorization':'Bearer a'}).get_json()['data']['apList']

    def test_original_mac_payload_and_persistent_readback(self):
        result=self.rename({'apMac':'00:11:22:33:44:55','alias':'客厅 BE72'})
        self.assertEqual(result['code'],0,result)
        self.assertEqual(self.rows()[0]['alias'],'客厅 BE72')
        self.store.bind_device('a','p','s1',{'mac':'001122334455','name':'old','isMain':True})
        self.assertEqual(self.rows()[0]['alias'],'客厅 BE72')
        from shinya_hub.router_alias import current
        from shinya_hub.devices import DeviceKey
        self.assertEqual(current(ProjectStore(Path(self.tmp.name)/'test.sqlite'),DeviceKey('a','p','s1')),'客厅 BE72')
        self.assertEqual(self.rows('q')[0]['alias'],'s2')

    def test_foreign_project_mac_is_rejected(self):
        self.assertEqual(self.rename({'apMac':'001122334466','alias':'wrong'})['code'],404)
        self.assertEqual(self.rename({'apMac':'001122334455','alias':'wrong'},token='b')['code'],404)

    def test_conflicting_selectors_and_readonly_are_rejected(self):
        self.assertEqual(self.rename({'projectId':'q','apMac':'001122334455','alias':'wrong'})['code'],404)
        self.assertEqual(self.rename({'sn':'s2','apMac':'001122334455','alias':'wrong'})['code'],404)
        self.assertEqual(self.rename({'apMac':'001122334455','alias':'wrong'},token='ro')['code'],403)

    def test_no_main_router_fallback_and_name_validation(self):
        for payload in ({'alias':'x'},{'apMac':'001122334455','alias':''},{'apMac':'001122334455','alias':'a\nb'},{'apMac':'001122334455','alias':'a'*65}):
            self.assertEqual(self.rename(payload)['code'],400,payload)
        self.assertEqual(self.rows()[0]['alias'],'s1')

    def test_removed_device_not_resurrected(self):
        self.store.unbind_device('a','p','s1')
        self.assertEqual(self.rename({'apMac':'001122334455','alias':'wrong'})['code'],404)


if __name__=='__main__':unittest.main(verbosity=2)
