import tempfile
import threading
import time
import unittest
from pathlib import Path

from shinya_hub.api import Principal, create_app, token_digest
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceKey, DeviceRegistry
from shinya_hub.store import ProjectStore
from shinya_hub.diagnosis import DiagnosticJobs, RESOURCE, report


class Driver:
    def __init__(self, finish=True, stale=False):
        self.started=False; self.finish=finish; self.stale=stale; self.calls=[]
    def execute(self, command):
        self.calls.append(command)
        if command.action == 'set':
            self.started=True
            return {'rcode':'00000000'}
        return {'rcode':'00000000','start_time':'old' if not self.started or self.stale else 'new',
                'process':'100%' if not self.started or self.finish else '30%',
                'list':[{'type':'negotiating_speed','list':[{'status':'failed','code':'0202',
                        'data':{'port':'LAN5/GAME'},'result':'Network port negotiation rate is abnormal'}]}]}


class DiagnosisTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.store=ProjectStore(Path(self.temp.name)/'hub.sqlite')
        self.registry=DeviceRegistry(self.store)
        self.keys=[]
        for tenant,project,sn in [('a','home','A'),('a','office','B'),('b','home','C')]:
            self.store.add_project(tenant,project,project)
            self.store.bind_device(tenant,project,sn,{'isMain':True})
            key=DeviceKey(tenant,project,sn);self.keys.append(key)
            self.registry.register(key,Driver())
        self.jobs=DiagnosticJobs(self.registry,timeout=.15,interval=.005)
    def tearDown(self):
        self.jobs.close();self.registry.close();self.temp.cleanup()
    def wait(self,key,job):
        deadline=time.monotonic()+1
        while time.monotonic()<deadline:
            value=self.jobs.status(key,job)
            if value['state'] in {'complete','failed'}:return value
            time.sleep(.005)
        self.fail('Diagnosis did not terminate')
    def test_real_failure_is_not_replaced_by_a_success_score(self):
        key=self.keys[0];started=self.jobs.start(key)
        result=self.wait(key,started['jobId'])
        self.assertEqual(result['state'],'complete')
        self.assertEqual(result['report']['errorCount'],1)
        self.assertIn('LAN5/GAME',result['report']['apProblemMain']['problemMainSolu'])
        self.assertEqual(self.store.cached(key.tenant,key.project,key.sn,RESOURCE)['value'],result['report'])
    def test_job_cannot_cross_project_or_tenant(self):
        started=self.jobs.start(self.keys[0])
        for key in self.keys[1:]:
            with self.assertRaises(PermissionError):self.jobs.status(key,started['jobId'])
    def test_old_completed_report_cannot_finish_a_new_diagnosis(self):
        self.registry.register(self.keys[0],Driver(stale=True))
        result=self.wait(self.keys[0],self.jobs.start(self.keys[0])['jobId'])
        self.assertEqual(result['state'],'failed');self.assertNotIn('report',result)
        self.assertIsNone(self.store.cached('a','home','A',RESOURCE))
    def test_bounded_workers_deduplicate_and_do_not_hold_device_lock(self):
        for key in self.keys:self.registry.register(key,Driver(finish=False))
        first=self.jobs.start(self.keys[0]);self.jobs.start(self.keys[1])
        self.assertEqual(self.jobs.start(self.keys[0])['jobId'],first['jobId'])
        with self.assertRaises(OverflowError):self.jobs.start(self.keys[2])
        began=time.monotonic()
        with self.registry.device_operation(self.keys[0]) as driver:
            driver.execute(UFCommand('dev_sta','get','sysinfo',None))
        self.assertLess(time.monotonic()-began,.1)
    def test_http_auth_and_selected_project(self):
        app=create_app(self.store,self.registry,{
            token_digest('owner'):Principal('a','A',True),token_digest('viewer'):Principal('b','B',False)})
        try:
            client=app.test_client()
            def call(token,body):return client.post('/api/v1/hub/diagnosis',json=body,
                headers={'Authorization':'Bearer '+token}).get_json()
            self.assertEqual(call('viewer',{'projectId':'home','operation':'start'})['code'],403)
            self.assertEqual(call('owner',{'projectId':'home','apSn':'B','operation':'start'})['code'],404)
            job=call('owner',{'projectId':'home','operation':'start'})['data']['jobId']
            self.assertEqual(call('owner',{'projectId':'office','operation':'status','jobId':job})['code'],403)
            self.assertEqual(call('viewer',{'projectId':'home','operation':'status','jobId':job})['code'],403)
        finally:app.extensions['shinya_diagnosis'].close()
    def test_unknown_status_is_not_reported_as_normal(self):
        value=report({'list':[{'type':'dns_config','list':[{'status':'skipped'}]}]})
        self.assertEqual(value['unknownCount'],1)
        self.assertIn('未完成',value['apProblemMain']['problemMainSolu'])

    def test_result_cards_are_actual_firmware_groups(self):
        result=self.wait(self.keys[0],self.jobs.start(self.keys[0])['jobId'])['report']
        self.assertEqual([g['type'] for g in result['groups']],['negotiating_speed'])
        self.assertEqual(result['groups'][0]['items'],result['items'])
        self.assertEqual(result['groups'][0]['status'],'failed')
        self.assertEqual(result['checkedCount'],1)

    def test_cancel_is_owned_and_stops_only_observation(self):
        self.registry.register(self.keys[0],Driver(finish=False))
        started=self.jobs.start(self.keys[0])
        with self.assertRaises(PermissionError):self.jobs.cancel(self.keys[1],started['jobId'])
        result=self.jobs.cancel(self.keys[0],started['jobId'])
        self.assertEqual(result['state'],'cancelled')
        self.assertNotIn('report',result)

if __name__=='__main__':unittest.main()
