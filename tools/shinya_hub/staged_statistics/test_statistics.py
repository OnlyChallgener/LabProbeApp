import json
import math
import tempfile
import threading
import time
import unittest
from datetime import datetime
from pathlib import Path

from shinya_hub.api import Principal, create_app, token_digest
from shinya_hub.commands import UFCommand
from shinya_hub.daily import CHINA, record
from shinya_hub.devices import DeviceKey, DeviceRegistry
from shinya_hub.statistics import (bounds, phy, record_resource, speed,
                                    stability, station_metrics)
from shinya_hub.store import ProjectStore


class StatisticsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = ProjectStore(Path(self.temp.name) / 'stats.sqlite')
        for tenant, project, sn in [('owner','home','A'),('owner','office','B'),('other','home','C')]:
            self.store.add_project(tenant, project, project)
            self.store.bind_device(tenant, project, sn, {'isMain': True})
        self.key = DeviceKey('owner','home','A')
        self.at = datetime(2026,10,9,10,tzinfo=CHINA).timestamp()

    def tearDown(self):
        self.temp.cleanup()

    def resource(self, module, value, at, key=None, data=None):
        record_resource(self.store,key or self.key,UFCommand('dev_sta','get',module,data),value,at)

    def station(self, at, **changes):
        row = {'mac':'AA1122334455','userIp':'192.0.2.5','band':'5G',
               'down':'1000','up':'2000','rxrate':'909M','rssi':'-55',**changes}
        record(self.store,'owner','home','A',[row],at)

    def test_curve_gaps_are_absent_and_native_chart_contains_only_finite_points(self):
        for offset,down in [(0,1024),(30,3072),(300,2048),(1200,4096)]:
            self.resource('flow',{'data':{'wan':{'down':down,'up':1024}}},self.at+offset,
                          data={'func':'interface_info'})
        self.resource('flow',{'data':{'wan':{'down':999999,'up':999999}}},self.at,
                      key=DeviceKey('owner','office','B'),data={'func':'interface_info'})
        result=speed(self.store,'owner','home','A',self.at+1200)
        self.assertEqual(result['bucketIndices'],[120,121,124])
        self.assertEqual(result['rxRateList'][120:],[16,16,None,None,32])
        self.assertEqual(result['timeList'][120],'10:00')
        self.assertEqual(len(result['data']['dataSets']),4)
        for group in result['data']['dataSets']:
            points=group['values']
            self.assertTrue(all(math.isfinite(p['x']) and math.isfinite(p['y']) for p in points))
            self.assertTrue(all(b['x']-a['x']==1 for a,b in zip(points,points[1:])))
        self.assertFalse(speed(self.store,'other','home','C',self.at)['bucketIndices'])
        self.assertFalse(speed(self.store,'owner','home','B',self.at)['bucketIndices'])
        json.dumps(result,allow_nan=False)

    def test_phy_means_missing_upload_midnight_and_unknown_gap(self):
        midnight=datetime(2026,10,10,tzinfo=CHINA).timestamp()
        self.station(midnight-10,rxrate='1G',rssi='-60')
        self.station(midnight+10,rxrate='200M',rssi='-70')
        self.station(midnight+30,rxrate='800M')
        prior=station_metrics(self.store,'owner','home',midnight-1)['aa:11:22:33:44:55']
        current=station_metrics(self.store,'owner','home',midnight+30)['aa:11:22:33:44:55']
        self.assertEqual(prior['rxRateAvg'],1000)
        self.assertAlmostEqual(current['rxRateAvg'],466.7)
        self.assertIsNone(current['txRateAvg'])
        self.assertEqual(current['rx_seconds'],30)
        self.station(midnight+300,rxrate='9999M')
        self.assertEqual(station_metrics(self.store,'owner','home',midnight+300)
                         ['aa:11:22:33:44:55']['rx_seconds'],30)
        self.assertEqual(bounds(midnight+1)[0],midnight)
        self.assertEqual(phy('54000Kbps'),54)
        self.assertIsNone(phy('--'))

    def test_recent_rates_cross_midnight_without_project_leak_or_outage_zero(self):
        midnight=datetime(2026,10,10,tzinfo=CHINA).timestamp()
        for at in (midnight-30,midnight+30):
            self.resource('flow',{'data':{'wan':{'down':125000,'up':250000}}},at,
                          data={'func':'interface_info'})
        self.resource('flow',{'data':{'wan':{'down':999999,'up':999999}}},midnight+40,
                      key=DeviceKey('owner','office','B'),data={'func':'interface_info'})
        result=speed(self.store,'owner','home','A',midnight+900)
        self.assertEqual(result['lastHourRx'],1)
        self.assertEqual(result['lastHourTx'],2)
        self.assertIsNone(speed(self.store,'other','home','A',midnight+900)['lastHourRx'])
        self.assertIsNone(speed(self.store,'owner','home','A',midnight+7200)['lastHourRx'])

    def test_quality_drops_require_successful_continuous_absence(self):
        self.station(self.at)
        self.station(self.at+30)
        record(self.store,'owner','home','A',[],self.at+40)
        record(self.store,'owner','home','A',[],self.at+45)
        metrics=station_metrics(self.store,'owner','home',self.at+45)['aa:11:22:33:44:55']
        self.assertEqual(metrics['dropCount'],1)
        self.assertEqual(metrics['lastDropAt'],self.at+40)
        self.station(self.at+50)
        record(self.store,'owner','home','A',[],self.at+500)
        self.assertEqual(station_metrics(self.store,'owner','home',self.at+500)
                         ['aa:11:22:33:44:55']['dropCount'],1)

    def test_score_uses_observed_dimensions_and_degrades_for_measured_failure(self):
        self.station(self.at)
        self.station(self.at+30)
        for offset,runtime,status,cpu in [(0,10000,'connected','5%'),(30,10030,'connected','5%')]:
            self.resource('overview',{'runtime':runtime,'status':status,'cpuutil':cpu},self.at+offset)
        self.resource('radioInfo',{'radio0':{'chutil':'10','channf':'-90'},
                                  'radio1':{'chutil':'1','channf':'-95'}},self.at)
        good=stability(self.store,'owner','home','A',self.at+30)
        self.assertEqual(good['curRadarScore']['apStableScore'],100)
        self.assertNotIn('smartScore',good['curRadarScore']) # Less than 5 observed station minutes.
        self.assertEqual(len(good['data']['data']['dataSets']),1)
        self.assertNotIn('avgRadarScore',good)
        self.assertNotIn('全国',json.dumps(good,ensure_ascii=False))
        self.resource('overview',{'runtime':10,'status':'disconnected','cpuutil':'99%'},self.at+60)
        self.resource('radioInfo',{'radio0':{'chutil':'99','channf':'-60'},
                                  'radio1':{'chutil':'99','channf':'-60'}},self.at+60)
        bad=stability(self.store,'owner','home','A',self.at+60)
        self.assertLess(float(bad['curRadarScore']['overallScore']),float(good['curRadarScore']['overallScore']))
        self.assertEqual(bad['curRadarScore']['apStableScore'],80)
        self.assertEqual(bad['curRadarScore']['linkStableScore'],50)
        self.assertFalse(bad['externalInternetProbe'])
        self.assertFalse(stability(self.store,'other','home','C',self.at)['metrics'])

    def test_real_zero_score_remains_visible_in_original_app(self):
        self.resource('overview',{'cpuutil':'100%'},self.at)
        result=stability(self.store,'owner','home','A',self.at)
        self.assertEqual(result['curRadarScore']['overallScore'],'0.0')
        self.assertEqual(result['data']['xAxis']['valueFormatter'],['CPU余量'])

    def test_api_response_contract_keeps_cached_history_while_device_is_unreachable(self):
        release=threading.Event()
        entered=threading.Event()
        finished=threading.Event()
        class Driver:
            def execute(self, command):
                entered.set()
                release.wait(10)
                finished.set()
                raise TimeoutError('offline')
        registry=DeviceRegistry(self.store,workers=1)
        registry.register(self.key,Driver())
        now=time.time()
        self.resource('flow',{'data':{'wan':{'down':4096,'up':1024}}},now-60,data={'func':'interface_info'})
        self.resource('overview',{'cpuutil':'5%','runtime':1000,'status':'connected'},now-60)
        self.store.health('owner','home','A',False)
        app=create_app(self.store,registry,{token_digest('test-token'):Principal('owner','Owner')})
        client=app.test_client()
        headers={'Authorization':'Bearer test-token'}
        try:
            for resource in ('getApSpeedRate','getNetStabilizeIndex'):
                reply=client.post('/homewlan/experienceVisual/'+resource,json={'projectId':'home'},headers=headers).get_json()
                self.assertEqual(reply['code'],0)
                self.assertIsInstance(reply['data'],dict)
                self.assertTrue(reply['data'])
            self.assertTrue(entered.wait(1))
            self.assertFalse(finished.is_set()) # Both replies arrived while the native read is blocked.
            foreign=client.post('/homewlan/experienceVisual/getApSpeedRate',
                                json={'projectId':'office','apSn':'A'},headers=headers).get_json()
            self.assertEqual(foreign['code'],404)
        finally:
            release.set()
            registry.close()


if __name__=='__main__':unittest.main()
