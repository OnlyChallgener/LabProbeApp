import sys,importlib.util,unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
spec=importlib.util.spec_from_file_location('shinya_hub.projection',Path('test/_analysis/oct12-offline/deployment/projection.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Tests(unittest.TestCase):
 def test_absent_zero_invalid_and_future_do_not_become_epoch_duration(self):
  for value in (None,'',0,'0',False,'bad','1970-01-01 00:00:00','2099-01-01 12:00:00'):
   self.assertIsNone(module.offline_time(value,1791790000))
 def test_actual_firmware_and_milliseconds_keep_the_same_time(self):
  self.assertEqual('2026-10-12 13:27:44',module.offline_time('2026-10-12 13:27:44',1791790000))
  self.assertEqual(module.offline_time(1791782864,1791790000),module.offline_time(1791782864000,1791790000))
if __name__=='__main__':unittest.main()
