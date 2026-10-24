import importlib.util,sys,tempfile,unittest,json,copy
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceKey
spec=importlib.util.spec_from_file_location('shinya_hub.router_tools_bridge',Path(__file__).with_name('router_tools_bridge_source.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Response:
    ok=True;content=b'{}'
    def __init__(self,value):self.value=value
    def json(self):return copy.deepcopy(self.value)
class Session:
    def __init__(self,states):self.states=states;self.headers={}
    def request(self,method,url,json=None,**kwargs):
        port=int(url.split(':')[2].split('/')[0]);s=self.states[port];path=url.split(str(port),1)[1].split('?')[0]
        if path=='/api/router/dashboard':
            serial=s.get('sn','a' if port==10001 else 'b')
            return Response({'ok':True,'router':'BE72','details':{'ap':{'serialNumber':serial},
                'identity':{'serialNumber':s.get('identitySn',serial)}}})
        if path=='/api/router/capabilities':return Response({'features':{'firewall':True,'ddns':True}})
        if method=='GET':return Response({'data':s})
        if path.endswith('/reorder'):
            s['order'][json['scope']]=json['uuids']
        elif method=='PUT':
            if not s.get('reject'):s['list'][0].update(json)
        return Response({'data':s})
    def close(self):pass
class Bridge(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.temp.name)/'db')
        for p,sn in [('one','a'),('two','b')]:
            self.store.add_project('t',p,p);self.store.bind_device('t',p,sn,{})
        self.key=DeviceKey('t','one','a')
        self.states={10001:{'list':[{'uuid':'r1','ruleName':'mine','direction':'forward','ipVersion':'dual','enable':'0'}],
            'order':{'forward_ipv4':['r1'],'forward_ipv6':['r1']}},10002:{'list':[{'uuid':'other'}]}}
        self.bridge=module.RouterToolsBridge(self.store,{('t','one','a'):(10001,'token1'),('t','two','b'):(10002,'token2')})
        self.fake=patch.object(module.requests,'Session',side_effect=lambda:Session(self.states));self.fake.start()
    def tearDown(self):self.fake.stop();self.temp.cleanup()
    def test_scope_and_foreign_rule_rejection(self):
        self.assertEqual(self.bridge.request(self.key,'firewall')['data']['list'][0]['uuid'],'r1')
        with self.assertRaises(LookupError):self.bridge.request(self.key,'firewall','update','other',{'ruleName':'changed'})
        with self.assertRaises(PermissionError):self.bridge.request(DeviceKey('t','two','a'),'firewall')
        self.states[10001]['sn']='b'
        with self.assertRaises(PermissionError):self.bridge.request(self.key,'firewall')
        self.states[10001]['sn']='a';self.states[10001]['identitySn']='b'
        with self.assertRaises(PermissionError):self.bridge.request(self.key,'firewall')
    def test_full_settings_and_real_readback(self):
        self.bridge.request(self.key,'firewall','update','r1',{'ruleName':'renamed','destPort':'443'})
        self.assertEqual(self.states[10001]['list'][0]['destPort'],'443')
        self.states[10001]['reject']=True
        with self.assertRaises(RuntimeError):self.bridge.request(self.key,'firewall','update','r1',{'destPort':'22'})
    def test_priority_rejects_incomplete_and_foreign_scope(self):
        with self.assertRaises(ValueError):self.bridge.request(self.key,'firewall','reorder',draft={'scope':'forward_ipv4','uuids':[]})
        self.bridge.request(self.key,'firewall','reorder',draft={'scope':'forward_ipv4','uuids':['r1']})
        self.assertEqual(self.states[10001]['order']['forward_ipv6'],['r1'])
    def test_certificate_dates_scope_and_persistence(self):
        r=self.bridge.request(self.key,'certificates','create',draft={'domain':'example.test','expiresOn':'2026-10-13'})['records'][0]
        self.assertEqual(self.bridge.request(DeviceKey('t','two','b'),'certificates')['records'],[])
        with self.assertRaises(LookupError):self.bridge.request(DeviceKey('t','two','b'),'certificates','delete',r['id'])
        self.bridge.request(self.key,'certificates','delete',r['id'])
        self.assertEqual(self.bridge.request(self.key,'certificates')['records'],[])
if __name__=='__main__':unittest.main()
