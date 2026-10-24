"""Rate precision, half-up display, missing/zero and project scope regression."""
import sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.store import ProjectStore
from shinya_hub.statistics import display_rate,record_station_interval,station_metrics,speed
from shinya_hub.daily import day_of

class Rates(unittest.TestCase):
 def test_half_up_not_bankers(self):
  for v,w in [(185.3,185),(185.5,186),(54.5,55),(1200.9,1201),(0,0)]:
   self.assertEqual(display_rate(v),w)
   self.assertIsInstance(display_rate(v),int)
  self.assertIsNone(display_rate(None))
 def test_precise_weighting_only_rounds_final_display(self):
  with tempfile.TemporaryDirectory() as tmp:
   store=ProjectStore(Path(tmp)/'rates.sqlite');now=1791600409
   store.add_project('t','p','home');store.bind_device('t','p','s',{'isMain':True})
   with store.connect() as db:
    record_station_interval(db,'t','p','aa:11:22:33:44:55',day_of(now),
       {'band':'5G','rxrate':'185.3M','txrate':'54.5M'},{},5)
    record_station_interval(db,'t','p','aa:11:22:33:44:55',day_of(now),
       {'band':'5G','rxrate':'185.7M','txrate':'54.7M'},{},15)
    record_station_interval(db,'t','foreign','aa:11:22:33:44:55',day_of(now),
       {'band':'5G','rxrate':'9999M','txrate':'9999M'},{},50)
   row=station_metrics(store,'t','p',now)['aa:11:22:33:44:55']
   self.assertAlmostEqual(row['rx_sum'],3712)
   self.assertAlmostEqual(row['tx_sum'],1093)
   self.assertEqual(row['rxRateAvg'],186);self.assertEqual(row['txRateAvg'],55)
   data=speed(store,'t','p','s',now)
   self.assertEqual(data['allDaySpeed'],186);self.assertEqual(data['allDaySpeedTx'],55)
   self.assertIsNone(speed(store,'other','p','s',now)['allDaySpeedTx'])
 def test_missing_and_measured_zero_remain_distinct(self):
  with tempfile.TemporaryDirectory() as tmp:
   store=ProjectStore(Path(tmp)/'rates.sqlite');now=1791600409
   with store.connect() as db:
    record_station_interval(db,'t','p','missing',day_of(now),{'band':'5G','rxrate':'5M'},{},5)
    record_station_interval(db,'t','p','zero',day_of(now),{'band':'5G','rxrate':'0M','txrate':'0M'},{},5)
   rows=station_metrics(store,'t','p',now)
   self.assertIsNone(rows['missing']['txRateAvg'])
   self.assertEqual(rows['zero']['txRateAvg'],0)
   self.assertEqual(rows['zero']['tx_seconds'],5)
   self.assertEqual(speed(store,'t','p','s',now)['allDaySpeedTx'],0)

if __name__=='__main__':unittest.main()
