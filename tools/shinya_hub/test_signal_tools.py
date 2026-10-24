import copy
import importlib.util
import sys
import tempfile
import unittest
from datetime import datetime
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0,'D:/Github/ShinyaHub')
scan_spec=importlib.util.spec_from_file_location('shinya_hub.hub_scan',Path('test/_analysis/oct12-signal-tools/scan/deployment/hub_scan.py'))
scan_module=importlib.util.module_from_spec(scan_spec);sys.modules[scan_spec.name]=scan_module;scan_spec.loader.exec_module(scan_module)
from shinya_hub.commands import UFCommand
from shinya_hub.devices import DeviceKey,DeviceRegistry
from shinya_hub.store import ProjectStore

def module(name):
    spec=importlib.util.spec_from_file_location('shinya_hub.'+name,Path('tools/shinya_hub')/(name+'_source.py'))
    value=importlib.util.module_from_spec(spec);sys.modules[spec.name]=value;spec.loader.exec_module(value);return value
schedule=module('signal_schedule');tools=module('signal_tools')

class Driver:
    def __init__(self):
        self.wireless={'networkId':'OWN','radioList':[{'radioIndex':'1','type':'2.4G','enable':'true'}],
                       'ssidList':[{'wlanId':'1','ssidName':'Home','password':'keep-secret','enable':'true','tmMode':'def','tmValue':{'tmngtName':'所有时段','slot':''}},
                                   {'wlanId':'3','ssidName':'Other','enable':'true','tmMode':'def','tmValue':{'tmngtName':'所有时段','slot':''}}],
                       'healthy':{'enable':'false','mode':'0','slot':'','tmngtName':''}}
        self.radio={'radioList':[{'radioIndex':'1','type':'2.4G','txpower':'auto','channel':'13'}, {'radioIndex':'2','type':'5G','txpower':'auto','channel':'52'}]}
        self.reject=False;self.writes=[]
    def execute(self,c):
        if c.action!='get':
            self.writes.append(c)
            if self.reject:return {'code':'0'} # dishonest acknowledgement
            if c.module=='wireless':self.wireless=copy.deepcopy(c.data)
            if c.module=='radio':self.radio=copy.deepcopy(c.data)
            return {'code':'0'}
        if c.module=='wireless':return copy.deepcopy(self.wireless)
        if c.module=='radio':return copy.deepcopy(self.radio)
        if c.module=='radio_channel':return {'band':'2.4G,5G','cur_channel':','.join(r['channel'] for r in self.radio['radioList'])}
        if c.module=='chanIntfc':return {'list':[{'channel':'1','intfc':'10'},{'channel':'13','intfc':'30'}]}
        if c.module=='shinya_exec':return {'bands':{'2.4G':[{'ssid':'neighbor','channel':13,'signal':-60,'width':20}], '5G':[]}}
        if c.module=='country_channel':return {'channel_2G':[{'channel':'1','NopOccupancy':'0'},{'channel':'13','NopOccupancy':'0'}]}
        raise ValueError(c.module)

class Contracts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=ProjectStore(Path(self.temp.name)/'db')
        self.store.add_project('t','p','BE72');self.store.bind_device('t','p','A',{})
        self.store.add_project('t','q','BE50');self.store.bind_device('t','q','B',{})
        self.r=DeviceRegistry(self.store,workers=1);self.key=DeviceKey('t','p','A');self.d=Driver();self.r.register(self.key,self.d)
    def tearDown(self):self.r.close();self.temp.cleanup()
    def test_cross_midnight_complement_and_roundtrip(self):
        value=[{'weekDays':[7],'beginEndTimes':[{'beginMinute':1320,'endMinute':360}]}]
        days=schedule.expand(value);self.assertEqual([[0,360]],days[1]);self.assertEqual([[1320,1440]],days[7])
        self.assertEqual(days,schedule.decode(schedule.encode(days)))
        self.assertEqual(days,schedule.complement(schedule.complement(days)))
    def test_wireless_off_times_are_complemented_and_other_ssid_preserved(self):
        raw={'wlanId':1,'enable':True,'timePolicies':[{'weekDays':[1,2],'beginEndTimes':[{'beginMinute':0,'endMinute':360}]}]}
        before=copy.deepcopy(self.d.wireless)
        tools.change_wireless(self.r,self.key,raw)
        self.assertEqual(before['ssidList'][1],self.d.wireless['ssidList'][1]);self.assertEqual('keep-secret',self.d.wireless['ssidList'][0]['password'])
        self.assertEqual([['06:00','23:59']],self.d.wireless['ssidList'][0]['tmValue']['slot']['mon'])
        self.assertEqual(raw['timePolicies'],tools.wireless_state(self.r,self.key,1)['timePolicies'])
        tools.change_wireless(self.r,self.key,{**raw,'enable':False,'timePolicies':[]})
        self.assertFalse(tools.wireless_state(self.r,self.key,1)['enable'])
    def test_failed_firmware_acknowledgement_does_not_create_cached_success(self):
        self.d.reject=True
        with self.assertRaises(RuntimeError):tools.change_wireless(self.r,self.key,{'enable':True,'timePolicies':[{'weekDays':[1],'beginEndTimes':[{'beginMinute':60,'endMinute':120}]}]},healthy=True)
        self.assertIsNone(self.store.cached('t','p','A',DeviceRegistry.resource(UFCommand('ac_config','get','wireless',None))))
    def test_healthy_slots_are_not_complemented(self):
        raw={'enable':True,'timePolicies':[{'weekDays':[1],'beginEndTimes':[{'beginMinute':60,'endMinute':120}]}]}
        tools.change_wireless(self.r,self.key,raw,healthy=True)
        self.assertEqual([['01:00','02:00']],self.d.wireless['healthy']['slot']['mon'])
        self.assertEqual(raw['timePolicies'],tools.wireless_state(self.r,self.key,healthy=True)['timePolicies'])
    def test_optimization_uses_measured_legal_channels_and_current_is_authoritative(self):
        result=tools.optimize(self.r,self.key,'2.4G');self.assertEqual('1',result['afterChannel'])
        self.assertEqual('52',self.d.radio['radioList'][1]['channel'])
        self.d.radio['radioList'][0]['channel']='13'
        self.assertEqual('13',tools.current(self.r,self.key)['channel24'])
        self.assertIsNone(tools.current(self.r,self.key)['botherIndex24'])
    def test_cron_switch_off_is_not_nonempty_array_and_project_isolation(self):
        raw={'timerTimePolicy':[{'isOpenTimer':False,'weekDays':[1],'time':{'minutes':180}}]}
        value=tools.cron(self.store,self.key,raw);self.assertFalse(value['isSetting'])
        other=tools.cron(self.store,DeviceKey('t','q','B'));self.assertFalse(other['timerTimePolicy'])
        value=tools.cron(self.store,self.key,{'timerTimePolicy':[{'weekDays':[],'time':0}]})
        self.assertFalse(value['isSetting']);self.assertFalse(value['timerTimePolicy'])
    def test_power_only_changes_power_and_rejects_foreign_radio(self):
        before=copy.deepcopy(self.d.radio)
        tools.power(self.r,self.key,{'radioList':[{'radioIndex':'1','txpower':'50','channel':'1'},{'radioIndex':'2','txpower':'50'}]})
        self.assertEqual(before['radioList'][0]['channel'],self.d.radio['radioList'][0]['channel'])
        with self.assertRaises(ValueError):tools.power(self.r,self.key,{'radioList':[{'radioIndex':'3','txpower':'50'}]})
    def test_original_cron_minutes_contract_and_missing_optimization_time(self):
        raw={'timerTimePolicy':[{'weekDays':[2],'minutes':180}]}
        value=tools.cron(self.store,self.key,raw)
        self.assertTrue(value['isSetting'])
        self.assertEqual(180,value['timerTimePolicy'][0]['minutes'])
        self.assertIsNone(tools.current(self.r,self.key)['lastOptimizeChannelTime5'])
    def test_legacy_deleted_timer_does_not_schedule_or_break_collector(self):
        self.store.save('t','p','A','hub.channel.cron_job',{'isSetting':True,'timerTimePolicy':[{'weekDays':[1,2,3,4,5,6,7],'time':0}]})
        timer=tools.ChannelTimer(self.r)
        try:
            timer.tick([self.key]);self.assertFalse(tools.cron(self.store,self.key)['isSetting'])
        finally:timer.close()
    def test_due_cron_dispatches_once_for_current_device_and_disabled_stays_idle(self):
        now=datetime(2026,10,12,3,0,tzinfo=tools.CHINA)
        tools.cron(self.store,self.key,{'timerTimePolicy':[{'weekDays':[1],'minutes':180}]})
        foreign=DeviceKey('t','q','B')
        timer=tools.ChannelTimer(self.r)
        try:
            with patch.object(tools,'optimize',return_value={}) as optimize:
                timer.tick([self.key,foreign],now)
                timer.pool.submit(lambda:None).result(timeout=5)
                # shutdown joins the one scheduled job before checking its final mark.
                timer.close()
                self.assertEqual([(self.r,self.key,'2.4G'),(self.r,self.key,'5G')],
                                 [call.args for call in optimize.call_args_list])
                timer.tick([self.key,foreign],now)
                self.assertEqual(2,optimize.call_count)
                tools.cron(self.store,self.key,{'timerTimePolicy':[{'weekDays':[],'time':0}]})
                timer.tick([self.key,foreign],now.replace(minute=1))
                self.assertEqual(2,optimize.call_count)
        finally:timer.close()

if __name__=='__main__':unittest.main()
