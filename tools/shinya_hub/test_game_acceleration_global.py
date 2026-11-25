"""Regression coverage for global acceleration and dependent services."""
import copy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, 'D:/Github/ShinyaHub')
from shinya_hub import game_acceleration as game
from shinya_hub.devices import DeviceKey, DeviceRegistry
from shinya_hub.store import ProjectStore


class Firmware:
    def __init__(self, state):
        self.state = dict(state)
        self.writes = []
        self.reject = False
        self.reenable = False

    def execute(self, command):
        if command.module == 'eweb':
            return {'gameTurbo': '1'}
        if command.module == 'rcgame':
            if command.action == 'set':
                self.writes.append(copy.deepcopy(command.data))
                if not self.reject:
                    self.state['game'] = command.data['game']
                    if 'study' in command.data:self.state['study'] = command.data['study']
                    self.state['enabled'] = 'true' if self.state['game'] == 'on' or any(
                        self.state.get(k) == 'on' for k in ('study', 'device', 'switch_game')) else 'false'
            elif self.reenable and self.writes:
                self.state['enabled'] = 'true'
            return copy.deepcopy(self.state)
        if command.module == 'overview':
            return {'enabled': self.state['enabled']}
        if command.module == 'shinya_game':
            return {'rcgame': copy.deepcopy(self.state), 'flowctrl': {}}
        raise AssertionError(command)


class GlobalGameState(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = ProjectStore(Path(self.tmp.name) / 'test.sqlite')
        self.key = DeviceKey('owner', 'project', 'router')
        self.store.add_project('owner', 'project', 'project')
        self.store.bind_device('owner', 'project', 'router', {})
        self.registry = DeviceRegistry(self.store)
        self.driver = Firmware({'enabled': 'true', 'game': 'off', 'study': 'on',
                                'device': 'on', 'switch_game': 'off'})
        self.registry.register(self.key, self.driver)

    def tearDown(self):
        self.registry.close()
        self.tmp.cleanup()

    def test_global_service_and_dependencies_are_not_misrepresented(self):
        self.assertTrue(game.status(self.registry, self.key)['isOpen'])
        with self.assertRaisesRegex(game.GameAccelerationError, '专属设备保障'):
            game.change(self.registry, self.key, False)
        self.assertEqual([], self.driver.writes)
        self.assertEqual('true', self.driver.state['enabled'])

    def test_hidden_study_category_is_cleared_when_closing_global_service(self):
        self.driver.state.update(enabled='true',game='off',study='on',device='off',switch_game='off')
        with patch.object(game.time,'sleep'):
            result=game.change(self.registry,self.key,False)
        self.assertEqual(result['openStatus'],0)
        self.assertEqual(self.driver.writes,[{'enabled':'default','game':'off','study':'off'}])
        self.assertEqual(self.driver.state['enabled'],'false')

    def test_enable_disable_and_post_write_readback(self):
        self.driver.state.update(enabled='false', game='off', study='off', device='off')
        with patch.object(game.time, 'sleep'):
            self.assertEqual(1, game.change(self.registry, self.key, True)['openStatus'])
            self.assertEqual(0, game.change(self.registry, self.key, False)['openStatus'])
        self.assertEqual('false', self.driver.state['enabled'])

    def test_unsupported_unknown_rejected_and_reenabled_do_not_succeed(self):
        self.driver.state.update(study='off', device='off', switch_game='unknown')
        with self.assertRaisesRegex(game.GameAccelerationError, '无法确认'):
            game.change(self.registry, self.key, False)
        self.assertFalse(self.driver.writes)
        self.driver.state.update(switch_game='off', game='on')
        self.driver.reject = True
        with self.assertRaises(game.GameAccelerationError):
            game.change(self.registry, self.key, False)
        self.driver.reject = False
        self.driver.reenable = True
        with self.assertRaises(game.GameAccelerationError):
            game.change(self.registry, self.key, False)

    def test_http_error_exposes_real_reason_and_denies_other_project(self):
        from shinya_hub.api import create_app, Principal, token_digest
        from shinya_hub.commands import UFCommand
        overview = UFCommand('dev_sta', 'get', 'overview', None)
        self.store.save('owner', 'project', 'router', self.registry.resource(overview),
                        {'rcgame_enabled': 'true', 'game': 'off'})
        app = create_app(self.store, self.registry,
                         {token_digest('test-token'): Principal('owner', 'writer', True)})
        try:
            client = app.test_client()
            headers = {'Authorization': 'Bearer test-token'}
            body = {'projectId': 'project', 'sn': 'router'}
            response = client.post('/api/v1/eSportAccelerate/closeESportAccelerate', json=body, headers=headers).get_json()
            self.assertEqual(409, response['code'])
            self.assertIn('专属设备保障', response.get('msg', ''))
            actual = client.post('/api/v1/api/device/command/sendCmd',
                json={**body, 'type': 2, 'cmd': 'dev_sta get -m overview'}, headers=headers).get_json()
            self.assertEqual(0, actual['code'])
            import json
            projected = json.loads(actual['data'])
            self.assertEqual(('true', 'off'), (projected['enabled'], projected['game']))
            other = client.post('/api/v1/eSportAccelerate/closeESportAccelerate',
                                json={**body, 'projectId': 'other'}, headers=headers).get_json()
            self.assertNotEqual(0, other['code'])
            self.assertFalse(self.driver.writes)
        finally:
            for obj in app.extensions.values():
                if hasattr(obj, 'close'):
                    obj.close()


if __name__ == '__main__':
    unittest.main()
