import importlib.util,sys,unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
import test_signal_scan_web as web_tests
spec=importlib.util.spec_from_file_location('shinya_hub.floor_bridge',Path('tools/shinya_hub/floor_bridge_source.py'))
floor=importlib.util.module_from_spec(spec);spec.loader.exec_module(floor)

class Tests(web_tests.WebTests):
    def setUp(self):
        super().setUp();self.bridge=floor.FloorBridge(self.service)
        session=self.session;post=session.post
        def request(method,url,**kwargs):
            if '/simulation/' not in url:return post(url,**kwargs)
            session.calls.append((url,kwargs));body=kwargs.get('params') or kwargs.get('json')
            if url.endswith('getImageUploadSignature'):
                data={'host':'https://shinya-prod.oss-cn-hangzhou.aliyuncs.com/','dir':'owned-project/','expire':'60','policy':'opaque','signature':'opaque'}
            else:data={'projectId':body['projectId'],'area':140,'apMode':['BE72-PRO'],'apPos':[{'x':.5,'y':.5}]}
            return web_tests.Response({'code':0,'data':data,'msg':''})
        session.request=request
    def test_read_uses_bound_cloud_project_and_returns_current_hub_project(self):
        value=self.bridge.request(self.b,floor.PREFIX+'getSignalSimulationResult',{'projectId':'900000002'})
        self.assertEqual('900000002',value['data']['projectId'])
        self.assertEqual(102,self.session.calls[-1][1]['params']['projectId'])
    def test_infer_image_requires_signature_for_this_router(self):
        raw={'projectId':'900000001','area':140,'frequency':'2.4G','image':'https://shinya-prod.oss-cn-hangzhou.aliyuncs.com/owned-project/image.jpg'}
        self.bridge.request(self.a,floor.PREFIX+'getImageUploadSignature',raw)
        before=len(self.session.calls)
        with self.assertRaises(ValueError):self.bridge.request(self.b,floor.PREFIX+'signalSimulationInfer',{**raw,'projectId':'900000002'})
        self.assertFalse(any(url.endswith('signalSimulationInfer') for url,_ in self.session.calls[before:]))
        with self.assertRaises(ValueError):self.bridge.request(self.a,floor.PREFIX+'signalSimulationInfer',{**raw,'image':'https://other.example/image.jpg'})
        self.assertEqual(0,self.bridge.request(self.a,floor.PREFIX+'signalSimulationInfer',raw)['code'])
    def test_render_preserves_normalized_coordinates_and_rejects_invalid_positions(self):
        raw={'area':140,'frequency':'5G','apMode':['BE72-PRO'],'apPos':[{'x':.2,'y':.7}]}
        self.assertEqual(raw,floor.FloorBridge.parameters('signalSimulationRender',raw))
        with self.assertRaises(ValueError):floor.FloorBridge.parameters('signalSimulationRender',{**raw,'apPos':[{'x':2,'y':.7}]})

if __name__=='__main__':unittest.main()
