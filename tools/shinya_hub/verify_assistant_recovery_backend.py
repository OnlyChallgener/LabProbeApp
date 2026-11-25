"""Exercise a hash-bound copy of the running assistant service, offline."""
import argparse
import importlib.util
import sys
import unittest
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--candidate',type=Path,default=ROOT/'test/assistant-recovery-20261104/backend')
    parser.add_argument('--unit-only',action='store_true');parser.add_argument('--report',type=Path)
    args=parser.parse_args();sys.path.insert(0,'D:/Github/ShinyaHub');sys.path.insert(0,'D:/Github/ShinyaHub/tests')
    spec=importlib.util.spec_from_file_location('shinya_hub.xiaoxing',args.candidate/'xiaoxing.py')
    service=importlib.util.module_from_spec(spec);sys.modules[spec.name]=service;spec.loader.exec_module(service)
    class Dispatch(unittest.TestCase):
        def test_read_cards_do_not_collect_unrelated_notifications(self):
            xx=service.XiaoxingService.__new__(service.XiaoxingService)
            xx._load=lambda *args:[]
            def blocked(*args):raise AssertionError('Synchronous notification collection blocked a card')
            xx.sync_notifications=blocked
            xx._assistant_stations=lambda *args,**kwargs:([],True)
            xx.watched_devices=lambda *args,**kwargs:[]
            xx._speed_summary=lambda *args:{'available':False}
            xx._save=lambda *args:None
            key=service.DeviceKey('owner','project','router')
            for q in ('如何修改WI-FI密码','如何开启儿童上网','灯光显示开关','设备连接记录','关注设备用网情况','NAS/摄像头状态检查','网络日报'):
                with self.subTest(q=q):
                    result=xx.handle_send(key,{'accessId':'project','apList':[]},{'messageDetail':q},allow_actions=True)
                    self.assertTrue(result['messages'])
            self.assertEqual(xx.handle_send(key,{}, {'messageDetail':'init'})['messages'],[])
        def test_release_notes_show_current_build_and_retain_history(self):
            notes={};exec(compile((args.candidate/'release_notes.py').read_bytes(),'release_notes.py','exec'),notes)
            rows=notes['recent']();self.assertEqual(len(rows),6)
            self.assertEqual(rows[0]['version'],'1.2.5-hub.50-dev')
            self.assertTrue(rows[0]['releaseTime'].startswith('2026-11-04'))
            self.assertTrue(rows[1]['version'].startswith('1.2.4-hub.'))
    suite=unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(Dispatch)])
    if not args.unit_only:suite.addTests(unittest.defaultTestLoader.loadTestsFromNames([
        'test_xiaoxing','test_xiaoxing_contracts','test_assistant_api',
        'test_assistant_diagnosis','test_assistant_observation_safety','test_assistant_cards']))
    result=unittest.TextTestRunner(verbosity=1).run(suite)
    if args.report:args.report.write_text(json.dumps({'run':result.testsRun,'failures':[test.id() for test,_ in result.failures],'errors':[test.id() for test,_ in result.errors]},indent=2),encoding='utf8')
    if not result.wasSuccessful():raise SystemExit(1)

if __name__=='__main__':main()
