"""Adapt observed firmware connection type to the original App's WifiBand."""
from pathlib import Path
root=Path('D:/Github/ShinyaHub')
path=root/'shinya_hub/projection.py'
source=path.read_text(encoding='utf-8')
anchor='def station_snapshots(store, tenant, project):'
helper='''def station_band(raw, identity):
    # Original getStaConnectStatusIcon and station filters compare wifiBand
    # with WifiBand.wire; native firmware reports connectType='wire' instead.
    transport = text(raw.get('connectType')).lower()
    if transport in {'wire', 'wired', 'ethernet', 'lan'}:
        return 'wire'
    band = text(raw.get('band'), raw.get('wifiBand'))
    if band:
        return band
    cached = text(identity.get('wifiBand'))
    # A current wireless connection cannot inherit an old wired snapshot.
    if transport in {'wifi', 'wireless', 'wlan'} and cached == 'wire':
        return ''
    return cached


'''
assert source.count(anchor)==1 and 'def station_band(' not in source
source=source.replace(anchor,helper+anchor)
old="'wifiBand':raw.get('band') or identity.get('wifiBand') or '',"
assert source.count(old)==1
path.write_text(source.replace(old,"'wifiBand':station_band(raw, identity),"),encoding='utf-8')
path=root/'tests/test_display.py'
source=path.read_text(encoding='utf-8')
source=source.replace('from shinya_hub.projection import station_snapshots','from shinya_hub.projection import station_snapshots, station_band')
anchor='    def test_projection_keeps_archived_and_live_identity_and_official_icon(self):'
tests='''    def test_original_wifi_band_uses_current_connection_not_stale_identity(self):
        self.assertEqual(station_band({'connectType':'wire','band':''}, {'wifiBand':'5G'}),'wire')
        self.assertEqual(station_band({'connectType':'wireless','band':'2.4G'}, {'wifiBand':'wire'}),'2.4G')
        self.assertEqual(station_band({'connectType':'wireless'}, {'wifiBand':'wire'}),'')
        self.assertEqual(station_band({'hostname':'fnos'}, {}),'')

    def test_wired_projection_preserves_native_transport_and_has_no_ssid(self):
        with tempfile.TemporaryDirectory() as directory:
            store=ProjectStore(Path(directory)/'hub.sqlite')
            store.add_project('owner','home','Home');store.bind_device('owner','home','A',{})
            rows=[{'mac':'AA1122334455','hostName':'fnos','connectType':'wire','band':''}]
            store.save('owner','home','A',DeviceRegistry.resource(USER_LIST),{'list':rows})
            store.merge_stations('owner','home','A',rows)
            store.save('owner','home','A',STATION_IDENTITIES,{'aa:11:22:33:44:55':{'wifiBand':'5G'}})
            result=station_snapshots(store,'owner',{'projectId':'home','apList':[{'sn':'A','online':True}]})[0]
            self.assertEqual(result['connectType'],'wire')
            self.assertEqual(result['wifiBand'],'wire')
            self.assertIsNone(result['wlanId'])
            self.assertTrue(result['online'])

'''
assert source.count(anchor)==1
path.write_text(source.replace(anchor,tests+anchor),encoding='utf-8')
print('Wired station original-UI field adaptation applied')
