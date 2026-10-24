import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from shinya_hub.devices import DeviceRegistry
from shinya_hub.mqtt_ingest import PushIngestor
from shinya_hub.projection import station_snapshots
from shinya_hub.store import ProjectStore
from shinya_hub.telemetry import USER_LIST


class OnlineStateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = ProjectStore(Path(self.temp.name) / 'hub.sqlite')
        self.bindings = [dict(tenant='owner', project='home', sn='A'),
                         dict(tenant='owner', project='office', sn='B'),
                         dict(tenant='other', project='home', sn='C')]
        for d in self.bindings:
            self.store.add_project(d['tenant'], d['project'], d['project'])
            self.store.bind_device(d['tenant'], d['project'], d['sn'], {})
        self.ingestor = PushIngestor(self.store, self.bindings)
        self.at = time.time()

    def tearDown(self):
        self.temp.cleanup()

    def test_light_frame_proves_router_alive_between_identity_updates(self):
        self.store.health('owner', 'home', 'A', False, checked=self.at)
        payload = json.dumps(dict(version=1, sn='A', runId='online-test-1', sequence=1,
            resources=[dict(module='user_list', observedAt=self.at, value={'list': []})]))
        self.ingestor.ingest('shinya/v1/A/up', payload, self.at)
        with patch('shinya_hub.store.time.time', return_value=self.at + 20):
            projects = self.store.projects('owner')
            self.assertTrue(projects[0]['online'])
            self.assertFalse(projects[1]['online'])
            self.assertFalse(self.store.projects('other')[0]['online'])
        with patch('shinya_hub.store.time.time', return_value=self.at + 91):
            self.assertFalse(self.store.projects('owner')[0]['online'])

    def test_new_frame_does_not_mark_present_archive_rows_offline(self):
        present = {'mac': 'AA1122334455', 'connectType': 'wire'}
        absent = {'mac': 'AA1122334466', 'connectType': 'wire'}
        self.store.merge_stations('owner', 'home', 'A', [present, absent], seen=self.at - 5)
        self.store.save('owner', 'home', 'A', DeviceRegistry.resource(USER_LIST),
                        {'list': [present]}, observed=self.at)
        self.store.health('owner', 'home', 'A', True)
        project = self.store.projects('owner')[0]
        rows = {r['mac'].replace(':', ''): r for r in station_snapshots(self.store, 'owner', project)}
        self.assertTrue(rows['AA1122334455']['online'])
        self.assertFalse(rows['AA1122334466']['online'])
        self.assertFalse(station_snapshots(self.store, 'other', self.store.projects('other')[0]))


if __name__ == '__main__':
    unittest.main()
