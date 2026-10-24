import importlib.util,sys,unittest,json
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
spec=importlib.util.spec_from_file_location('shinya_hub.station_controls',Path('tools/shinya_hub/station_controls_source.py'))
controls=importlib.util.module_from_spec(spec);spec.loader.exec_module(controls)
def row(kind,at,fraction=0,source='hostapd'):
 return {'kind':kind,'happened':at,'source':source,'event_key':f'mac:{kind}:boot:{at+fraction}'}
class History(unittest.TestCase):
 def test_same_second_disconnect_then_reconnect_keeps_original_start(self):
  values=[row(1,100,.1),row(1,200,.9),row(2,200,.8),row(2,500,.5)]
  actual=controls.normalize_events(values)
  self.assertEqual([1,2],[v['eventType'] for v in actual]);self.assertLess(abs(100000-actual[0]['eventTime']),1000);self.assertEqual(400400,actual[1]['eventTime']-actual[0]['eventTime'])
 def test_real_same_second_short_session_retains_order(self):
  actual=controls.normalize_events([row(2,100,.9),row(1,100,.1)])
  self.assertEqual([1,2],[v['eventType'] for v in actual])
 def test_accounting_log_does_not_inject_a_false_offline_between_associations(self):
  values=[row(1,100,.1),row(2,200,.7),row(1,200,.8),row(2,150,source='native_sta_log')]
  actual=controls.normalize_events(values)
  self.assertEqual([1],[v['eventType'] for v in actual])
 def test_real_offline_period_and_missing_data_are_not_filled(self):
  actual=controls.normalize_events([row(1,100),row(2,200),row(1,600),row(1,650,source='native_user_list')])
  self.assertEqual([100000,200000,600000],[v['eventTime'] for v in actual])
 def test_live_ipad_duplicate_bursts_are_coalesced(self):
  raw=json.loads(Path('test/_analysis/oct12-followup/be72-events.txt').read_text())
  values=[{'kind':v['eventType'],'happened':v['eventTime'],'source':v['source'],'event_key':v['eventKey']} for v in raw]
  actual=controls.normalize_events(values)
  self.assertLess(len(actual),len(raw)//3)
  self.assertTrue(all(v['eventTime']<=w['eventTime'] for v,w in zip(actual,actual[1:])))
if __name__=='__main__':unittest.main()
