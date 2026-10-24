import copy,importlib.util,json,sys,tempfile,time,unittest
from pathlib import Path
from threading import Event

sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.devices import DeviceKey,DeviceRegistry
from shinya_hub.store import ProjectStore

def overlay(name):
    spec=importlib.util.spec_from_file_location('shinya_hub.'+name,Path('test/_analysis/oct12-signal-tools/scan/deployment')/(name+'.py'))
    module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module);return module
import test_signal_tools # Load the reviewed firmware command helpers.
hub_scan=overlay('hub_scan');scan=overlay('signal_scan');web=overlay('official_channels')

class ScanTests(unittest.TestCase):
    def test_live_jobs_are_scoped_coalesced_and_failed_refresh_keeps_snapshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=ProjectStore(Path(tmp)/'db');registry=DeviceRegistry(store,workers=1)
            store.add_project('t','one','BE72');store.bind_device('t','one','A',{})
            store.add_project('t','two','BE50');store.bind_device('t','two','B',{})
            first=DeviceKey('t','one','A');second=DeviceKey('t','two','B')
            started=Event();release=Event()
            class Driver:
                calls=0;fail=False
                def execute(self,command):
                    self.calls+=1;started.set();release.wait(2)
                    if self.fail:raise RuntimeError('scan failed')
                    return {'bands':{'2.4G':[{'ssid':'same-name','bssid':'aa','channel':1,'signal':-60,'width':20,'isSelfSsid':True}],
                                     '5G':[{'ssid':'neighbor','bssid':'bb','channel':36,'signal':-80,'width':80}]}}
            driver=Driver();registry.register(first,driver);service=scan.SignalScan(registry)
            try:
                self.assertEqual('started',service.start(first)['state']);self.assertTrue(started.wait(2))
                self.assertEqual('running',service.start(first)['state']);self.assertFalse(service.last(second)['inScan'])
                self.assertIsNone(service.last(second)['channel24Detail']);release.set();service.close()
                result=service.last(first);self.assertEqual(0,result['channel24Detail']['channelInterfere'][0]['botherIndex'])
                self.assertEqual(1,driver.calls)
                service=scan.SignalScan(registry);driver.fail=True;service.start(first);service.close()
                failed=service.last(first);self.assertEqual(result['scanTime'],failed['scanTime']);self.assertIn('scanError',failed)
            finally:service.close();registry.close()
    def test_same_ssid_name_on_another_router_remains_a_neighbor(self):
        entry={'ssid':'Home','channel':1,'signal':-60,'width':20,'isSelfSsid':False}
        self.assertFalse(hub_scan.ssid_rows([entry],['Home'])[0]['isSelfSsid'])
        self.assertGreater(hub_scan.interference([entry],(1,))[0]['botherIndex'],0)

class Response:
    status_code=200;content=b'{}'
    def __init__(self,value):self.value=value
    def json(self):return self.value

class Session:
    def __init__(self,clock):self.calls=[];self.clock=clock;self.wrong=False;self.soft_expired=False
    def post(self,url,**kw):
        self.calls.append((url,kw))
        if url.endswith('accountPasswordLogin'):return Response({'code':0,'data':{'token':'fresh-official'}})
        if url.endswith('getDeviceInfoList2'):
            if self.soft_expired:
                self.soft_expired=False;return Response({'code':0,'data':None})
            sn='A' if kw['json']['projectId']==101 else 'B'
            return Response({'code':0,'data':{'apList':[{'sn':sn}]}})
        sn=kw['json']['sn'];meta={'sn':'foreign' if self.wrong else sn,'ip':'47.104.22.0','port':63001,
            'staIp':'127.0.0.1','remotePort':80,'status':'ESTABLISHED','finishTime':int(self.clock()*1000),'validTime':180}
        return Response({'code':0,'data':'http://47.104.22.0:63001/cgi-bin/luci/?opaque=login','msg':json.dumps(meta)})
    def close(self):pass

class WebTests(unittest.TestCase):
    def setUp(self):
        self.now=[1791741000.0];self.session=Session(lambda:self.now[0]);self.a=DeviceKey('t','one','A');self.b=DeviceKey('t','two','B')
        self.service=web.OfficialChannels([{'tenant':'t','project':p,'sn':sn,'officialProjectId':pid,
            'account':'same','password':'test','token':'old'} for p,sn,pid in [('one','A',101),('two','B',102)]],
            session_factory=lambda:self.session,clock=lambda:self.now[0])
    def test_refresh_before_allocation_share_account_and_cache_by_router_expiry(self):
        self.service.request(self.a,'homeAp/getTunnelUrl');self.service.request(self.a,'homeAp/getTunnelUrl')
        self.service.request(self.b,'homeAp/getTunnelUrl')
        calls=self.session.calls
        self.assertEqual(1,sum(url.endswith('accountPasswordLogin') for url,_ in calls))
        allocations=[kw for url,kw in calls if url.endswith('getTunnelUrl')]
        self.assertEqual([101,102],[kw['json']['projectId'] for kw in allocations])
        self.assertTrue(all(kw['headers']['Authorization']=='Bearer fresh-official' for kw in allocations))
        self.assertEqual((4,50),allocations[0]['timeout'])
        self.now[0]+=181;self.service.request(self.a,'homeAp/getTunnelUrl')
        self.assertEqual(3,sum(url.endswith('getTunnelUrl') for url,_ in calls))
    def test_code_zero_null_authorization_is_refreshed_before_one_write(self):
        self.session.soft_expired=True;self.service.request(self.a,'homeAp/getTunnelUrl')
        self.assertEqual(1,sum(url.endswith('getTunnelUrl') for url,_ in self.session.calls))
        self.assertEqual(2,sum(url.endswith('accountPasswordLogin') for url,_ in self.session.calls))
    def test_wrong_router_link_is_refused_and_not_cached(self):
        self.session.wrong=True
        with self.assertRaises(web.OfficialChannelError):self.service.request(self.a,'homeAp/getTunnelUrl')
        self.assertEqual({},self.service.web_links)

if __name__=='__main__':unittest.main()
