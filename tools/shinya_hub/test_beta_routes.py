import tempfile
import unittest
from pathlib import Path
from shinya_hub.api import Principal, create_app, token_digest
from shinya_hub.devices import DeviceKey, DeviceRegistry
from shinya_hub.store import ProjectStore


class Driver:
    def __init__(self, value): self.value, self.calls = value, []
    def execute(self, command): self.calls.append(command); return self.value


class BetaProjectTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = ProjectStore(Path(self.temp.name) / 'db.sqlite')
        self.registry = DeviceRegistry(self.store)
        self.drivers = {}
        for tenant, project, sn in [('owner', 'home', 'A'), ('owner', 'office', 'B'), ('other', 'home', 'C')]:
            self.store.add_project(tenant, project, project)
            self.store.bind_device(tenant, project, sn, {'name': sn})
            driver = Driver({'cur': 'current-' + sn, 'new': {'firmwareList': '[]'}})
            self.drivers[sn] = driver
            self.registry.register(DeviceKey(tenant, project, sn), driver)
        self.client = create_app(self.store, self.registry, {
            token_digest('token'): Principal('owner', 'Owner', True)}).test_client()
    def tearDown(self): self.registry.close(); self.temp.cleanup()
    def post(self, resource, body):
        return self.client.post('/api/v1/homeAp/' + resource, json=body,
                                headers={'Authorization': 'Bearer token'}).get_json()
    def check(self, project, sns):
        return self.post('isSoftVersionUpdateList', {'projectId': project, 'dataList': [{'sn': s} for s in sns]})
    def test_each_project_only_returns_its_requested_routers(self):
        for project, sn in [('home', 'A'), ('office', 'B')]:
            self.assertEqual(self.post('getBetaStatus', {'projectId': project})['data'], 1)
            response = self.check(project, [sn])
            self.assertEqual(response['code'], 0)
            self.assertEqual([r['sn'] for r in response['data']], [sn])
            self.assertEqual(response['data'][0]['code'], 2)
        self.assertFalse(self.drivers['C'].calls)
    def test_entire_list_validated_before_any_device_read(self):
        for sns in [['A', 'B'], ['A', 'C']]:
            self.assertEqual(self.check('home', sns)['code'], 404)
        self.assertFalse(any(d.calls for d in self.drivers.values()))
    def test_missing_or_foreign_project_cannot_guess_from_sn(self):
        self.assertEqual(self.check('', ['A'])['code'], 404)
        self.assertEqual(self.check('unknown', ['A'])['code'], 404)
        self.assertFalse(any(d.calls for d in self.drivers.values()))
    def test_empty_or_invalid_native_result_is_not_latest_version(self):
        self.drivers['A'].value = {'cur': None, 'new': None}
        response = self.check('home', ['A'])
        self.assertEqual(response['code'], 503)
        self.assertIsNone(response['data'])
    def test_beta_candidate_uses_original_row_contract(self):
        self.drivers['A'].value['new']['firmwareList'] = [{'version': 'beta-A', 'releaseNotes': ['one', 'two']}]
        response = self.check('home', ['A'])
        self.assertEqual(response['data'][0], {'sn': 'A', 'code': 6, 'softVersion': 'beta-A',
                                              'updateInfo': 'one\ntwo', 'updateInfoUrl': ''})


if __name__ == '__main__': unittest.main()
