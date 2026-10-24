import tempfile
import unittest
from pathlib import Path
from shinya_hub.api import Principal,create_app,token_digest
from shinya_hub.devices import DeviceRegistry
from shinya_hub.projection import station_snapshots
from shinya_hub.store import ProjectStore
from shinya_hub.station_identity import choices,project_choice


class StationIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'hub.sqlite'
        self.store=ProjectStore(self.path)
        for tenant,project,sn in [('owner','home','A'),('owner','office','B'),('other','home','C')]:
            self.store.add_project(tenant,project,project);self.store.bind_device(tenant,project,sn,{})
            self.store.merge_stations(tenant,project,sn,[dict(mac='AA1122334455',type='pc',manufacturer='unknown')])
        self.registry=DeviceRegistry(self.store)
        app=create_app(self.store,self.registry,{token_digest('owner'):Principal('owner','Owner',True),
                                              token_digest('view'):Principal('owner','View',False)})
        self.client=app.test_client()
    def tearDown(self):self.registry.close();self.temp.cleanup()
    def save(self,project='home',token='owner',**values):
        return self.client.post('/api/v1/hub/station/identity',json=dict(projectId=project,
            mac='AA1122334455',type='nas',manufacturer='ugreen',**values),
            headers={'Authorization':'Bearer '+token}).get_json()
    def test_choice_survives_native_updates_restart_and_isolates_projects(self):
        self.assertEqual(self.save()['code'],0)
        self.store.merge_stations('owner','home','A',[dict(mac='AA1122334455',devType='pc',manufacture='old')])
        store=ProjectStore(self.path)
        row=station_snapshots(store,'owner',store.projects('owner')[0])[0]
        self.assertEqual((row['type'],row['typeLevel2'],row['manufacturer']),('nas','nas','ugreen'))
        self.assertFalse(choices(store,'owner','office'));self.assertFalse(choices(store,'other','home'))
    def test_viewer_and_unknown_project_cannot_write(self):
        self.assertEqual(self.save(token='view')['code'],403)
        self.assertEqual(self.save(project='foreign')['code'],404)
        self.assertFalse(choices(self.store,'owner','home'))
    def test_manual_nas_updates_generic_parameter_label_but_keeps_specific_model(self):
        saved={'type':'pc','typeLevel2':'nas','manufacturer':'fnos'}
        row=project_choice({'type':'pc','recommend':'电脑','remark':'飞牛'},saved)
        self.assertEqual((row['type'],row['recommend'],row['remark']),('nas','NAS','飞牛'))
        self.assertEqual(project_choice({'recommend':'DH4300Plus'},saved)['recommend'],'DH4300Plus')

if __name__=='__main__':unittest.main()
