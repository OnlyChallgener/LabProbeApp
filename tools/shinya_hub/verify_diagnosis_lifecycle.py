"""Regression checks: measurement outcomes must never replace task lifecycle."""
import copy
import importlib.util
import sys
import unittest
from pathlib import Path

sys.path.insert(0, 'D:/Github/ShinyaHub')
candidate = Path(sys.argv.pop(1))
spec = importlib.util.spec_from_file_location('shinya_hub.xiaoxing', candidate)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class Lifecycle(unittest.TestCase):
    def setUp(self):
        self.service = module.XiaoxingService.__new__(module.XiaoxingService)
        self.service.pings = None
        self.records = {'task': {'uuid': 'task', '_jobId': 'job', 'diagnosisTime': 123}}
        self.service._load = lambda *args: copy.deepcopy(self.records)
        self.service._save = lambda key, resource, value: self.records.update(copy.deepcopy(value))
        self.key = module.DeviceKey('owner', 'project', 'router')

    def view(self, state, outcome):
        checks = [
            {'category': 'interfereStatus', 'code': '5g_connect_24g',
             'status': outcome, 'available': True, 'data': {'affected': []}},
            {'category': 'loadStatus', 'code': 'high_sta_load',
             'status': outcome, 'available': True, 'data': {'onlineCnt': 2, 'bandWide': 100}},
        ]
        return self.service._diagnosis_view(self.key, {}, 'task', {
            'state': state, 'progress': 100 if state == 'complete' else 40,
            'report': {'items': checks, 'categories': {}},
        })

    def test_running_and_terminal_states_survive_every_measurement_outcome(self):
        for state in ('queued', 'running', 'complete', 'failed', 'cancelled'):
            for outcome in ('success', 'failed', 'measured', 'unavailable'):
                with self.subTest(state=state, outcome=outcome):
                    result = self.view(state, outcome)
                    self.assertEqual(result['state'], state)
                    self.assertEqual(result['detail']['state'], state)

    def test_completed_partial_or_abnormal_measurements_are_not_a_terminated_task(self):
        for outcome in ('success', 'failed', 'measured', 'unavailable'):
            with self.subTest(outcome=outcome):
                result = self.view('complete', outcome)
                self.assertEqual(result['messages'][0]['diagnosisStatus'], 'success')
                self.assertFalse(result['detail']['healthConclusionComplete'])
                self.assertEqual(result['detail']['report']['items'][0]['status'], outcome)

    def test_legacy_shadowed_terminal_record_uses_saved_job_without_restarting(self):
        self.view('complete', 'success')
        self.records['task']['state'] = 'success'
        class NoLookup:
            def status(self, *args):
                raise AssertionError('Completed evidence must not require a live job')
        self.service.diagnosis = NoLookup()
        result = self.service.diagnosis_status(self.key, {}, 'task')
        self.assertEqual(result['state'], 'complete')
        self.assertEqual(result['messages'][0]['diagnosisStatus'], 'success')


unittest.main()
