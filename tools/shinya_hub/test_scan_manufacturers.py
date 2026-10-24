import sys,unittest
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.scan_manufacturers import manufacturer,canonical
from shinya_hub.hub_scan import payload

class ScanBrands(unittest.TestCase):
 def test_native_self_and_virtual_bssid_use_official_ruijie_brand(self):
  for bssid in ('10:5f:02:05:80:69','12:5f:02:25:80:6a'):
   self.assertEqual('ruijie',manufacturer({'bssid':bssid,'isSelfSsid':True}))
 def test_neighbor_registry_matches_official_reference_without_ssid_guessing(self):
  for bssid,brand in [('5c:de:34:4f:54:23','mercury'),('f8:48:fd:2c:8b:29','china mobile'),('64:6e:97:63:0e:3a','tp-link'),('54:55:d5:40:c9:34','huawei'),('a8:74:84:1b:b0:c9','zte')]:
   self.assertEqual(brand,manufacturer({'bssid':bssid,'ssid':'a misleading WiFi name'}))
  self.assertEqual('',manufacturer({'bssid':'42:b1:5c:2d:1a:1c','ssid':'TP-LINK_fake'}))
 def test_company_intelligent_is_not_intel(self):
  self.assertEqual('Chengdu Quanjing Intelligent Technology Co.,Ltd',canonical('Chengdu Quanjing Intelligent Technology Co.,Ltd'))
  self.assertEqual('360',canonical('Shenzhen Qihu Intelligent Technology Company Limited'))
 def test_payload_keeps_scan_timestamp_and_inserts_brand(self):
  v=payload({'observedAt':100,'bands':{'2.4G':[{'ssid':'home','bssid':'12:5f:02:25:80:6a','isSelfSsid':True,'channel':1,'signal':0}] }},[])
  self.assertEqual(100000,v['scanTime']);self.assertEqual('ruijie',v['channel24Detail']['channelSsidList'][0]['manufacturer'])
if __name__=='__main__':unittest.main()
