"""Observed regressions: original MAC list, rule-name delete, cleared badges."""
import os,unittest,copy,time
os.environ['SHINYA_TEST_STAGE']='test/_analysis/oct12-device-state/deployment/shinya_hub'
from test_device_controls import Contracts,controls,module,UFCommand,DeviceKey
projection=module('projection')

class StateContracts(Contracts):
 def test_only5g_list_is_original_compact_mac_strings(self):
  controls.change_only5g(self.r,self.key,'02:00:00:00:00:01',True)
  self.assertIn('020000000001',controls.read_only5g(self.r,self.key,True))
  controls.change_only5g(self.r,self.key,'02:00:00:00:00:01',True)
  self.assertEqual(2,len(self.r.driver.band['apselect_list']))
  controls.change_only5g(self.r,self.key,'02:00:00:00:00:01',False)
  self.assertNotIn('020000000001',controls.read_only5g(self.r,self.key,True))
 def test_unlimited_deletes_original_rule_name_then_custom_add(self):
  c=UFCommand('dev_config','del','flowctrl_udp',{'rulename':['192.168.5.43']})
  with self.assertRaises(PermissionError):controls.change_rate(self.r,self.key,c,{'192.168.6.43'})
  self.assertFalse(self.r.driver.writes)
  controls.change_rate(self.r,self.key,c,{'192.168.5.43'})
  self.assertFalse(self.r.driver.rate['list'])
  row={'comment':'192.168.5.43','ipRange':'192.168.5.43-192.168.5.43','mode':'per_ip','intf':'br-wan',
       'upRate':'5000','downRate':'7000','enable':'on'}
  controls.change_rate(self.r,self.key,UFCommand('dev_config','add','flowctrl_udp',{'list':[row]}),{'192.168.5.43'})
  self.assertEqual(('5000','7000'),tuple(self.r.driver.rate['list'][0][n] for n in ('upRate','downRate')))
 def test_event_scope_three_day_range_and_short_disconnection(self):
  from datetime import datetime,timedelta
  today=datetime.now(controls.CHINA).replace(hour=0,minute=0,second=0,microsecond=0)
  at=int((today-timedelta(days=1)).timestamp())+3600
  mac='02:00:00:00:00:01'
  values={'events':[{'mac':mac,'eventType':kind,'eventTime':at+offset,'eventKey':str(offset),'source':'native'}
                    for kind,offset in ((1,0),(2,600),(1,630))]}
  controls.record_events(self.store,self.key,values)
  other=DeviceKey('t','q','be50')
  controls.record_events(self.store,other,{'events':[{'mac':mac,'eventType':2,'eventTime':at+1000,'eventKey':'foreign'}]})
  class History:
   def execute(self,command):return {'events':[]}
  self.r.driver=History()
  rows=controls.event_history(self.r,self.key,mac,(today-timedelta(days=10)).strftime('%Y-%m-%d'))['rows']
  self.assertEqual([at+630,at+600,at],[r['eventTime']//1000 for r in rows])
  self.assertTrue(rows[1]['shortOff'])

if __name__=='__main__':unittest.main()
