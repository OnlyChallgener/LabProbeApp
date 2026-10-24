"""Apply two narrowly scoped compatibility fixes to the independent HUB."""
from pathlib import Path

ROOT = Path('D:/Github/ShinyaHub')
path = ROOT / 'shinya_hub/projection.py'
source = path.read_text(encoding='utf8')
old = "row['isBlack'] = '1' if mac_key(raw['mac']) in acl[sn] else '0'"
assert source.count(old) == 1
source = source.replace(old, "row['isBlack'] = 1 if mac_key(raw['mac']) in acl[sn] else 0")
path.write_text(source, encoding='utf8')

path = ROOT / 'shinya_hub/devices.py'
source = path.read_text(encoding='utf8')
old = '''                if not matches(command.data, readback):'''
new = '''                comparison = readback
                # BE50 omits the optional Mesh list when it is empty. Only this
                # known devLed representation is equivalent; actual LED state
                # and nonempty per-node settings must still match the firmware.
                if (command.namespace, command.module) == ('ac_config', 'devLed') and (
                        isinstance(command.data, dict) and command.data.get('list') == [] and
                        isinstance(readback, dict) and 'list' not in readback):
                    comparison = {**readback, 'list': []}
                if not matches(command.data, comparison):'''
assert source.count(old) == 1
path.write_text(source.replace(old, new), encoding='utf8')

path = ROOT / 'tests/test_writeback.py'
source = path.read_text(encoding='utf8')
anchor = '    def test_old_read_cannot_overwrite_verified_configuration(self):'
tests = '''    def test_led_empty_optional_list_may_be_omitted_by_be50(self):
        class Be50Driver(ConfigDriver):
            def execute(self, command):
                result = super().execute(command)
                if command.action == 'get': result.pop('list', None)
                return result
        with tempfile.TemporaryDirectory() as directory:
            store, registry, key = self.setup_registry(directory, Be50Driver())
            try:
                registry.execute_write(key, UFCommand('ac_config', 'set', 'devLed',
                                                     {'led_all': 'restore', 'list': []}))
                getter = UFCommand('ac_config', 'get', 'devLed', None)
                cached = store.cached('owner', 'home', 'A1', registry.resource(getter))['value']
                self.assertEqual(cached['led_all'], 'restore')
                self.assertNotIn('list', cached)
                with self.assertRaises(RouterRpcExecutionError):
                    registry.execute_write(key, UFCommand('ac_config', 'set', 'devLed',
                        {'led_all': 'close', 'list': [{'sn': 'A1', 'led': 'close'}]}))
            finally:
                registry.close()

'''
assert source.count(anchor) == 1
path.write_text(source.replace(anchor, tests + anchor), encoding='utf8')

path = ROOT / 'tests/test_online_state.py'
source = path.read_text(encoding='utf8')
source = source.replace('from shinya_hub.telemetry import USER_LIST',
                        'from shinya_hub.telemetry import USER_LIST\nfrom shinya_hub.blacklist import GLOBAL, SSIDS')
anchor = '    def test_new_frame_does_not_mark_present_archive_rows_offline(self):'
tests = '''    def test_unblocked_station_is_falsy_in_original_app_detail(self):
        rows = [{'mac': 'AA1122334455'}, {'mac': 'AA1122334466'}]
        self.store.merge_stations('owner', 'home', 'A', rows)
        self.store.save('owner', 'home', 'A', DeviceRegistry.resource(GLOBAL),
            {'type': 'deny', 'macList': [{'mac': 'AA1122334466'}], 'axMacList': []})
        self.store.save('owner', 'home', 'A', DeviceRegistry.resource(SSIDS), {'ssidList': []})
        projected = station_snapshots(self.store, 'owner', self.store.projects('owner')[0])
        by_mac = {r['mac'].replace(':', ''): r for r in projected}
        self.assertEqual(by_mac['AA1122334455']['isBlack'], 0)
        self.assertFalse(by_mac['AA1122334455']['isBlack'])
        self.assertEqual(by_mac['AA1122334466']['isBlack'], 1)
        self.assertTrue(by_mac['AA1122334466']['isBlack'])

'''
assert source.count(anchor) == 1
path.write_text(source.replace(anchor, tests + anchor), encoding='utf8')
print('Control readback compatibility fixes applied')
