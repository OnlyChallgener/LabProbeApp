"""Meaningful scoped adapter tests using recorded minutes and native fakes."""
import copy,json,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.children import ChildrenService,ChildError,CONFIG,STATE,native_policies,windows
from shinya_hub.store import ProjectStore
from shinya_hub.devices import DeviceKey,DeviceRegistry
from shinya_hub.api import create_app,Principal,token_digest
from shinya_hub.catalog import AppCatalog

UID='9A59FF88998744D4B7B4FB05E3E0255C'
MAC='1a:9c:c5:c5:b7:bb'
class Driver:
    def __init__(self):
        self.users=[{'uid':UID,'name':'测试电脑','macs':[MAC],'block':0,'pause':0,'policies':[]}]
        self.writes=[]
    def execute(self,c):
        if c.module=='shinya_child':
            if c.data.get('action')=='runtime':
                user=next(u for u in self.users if u['uid']==c.data['uid'])
                return {'user':{'uid':user['uid'],'policy':[p['id'] for p in user['policies'] if p['type']!='0']},
                    'sets':{'child_guard_block':{m:True for m in user['macs']} if user.get('block') else {},
                            'child_guard_skip':{m:True for m in user['macs']} if user.get('pause') else {}}}
            return {'users':copy.deepcopy(self.users),'observedAt':1,'rdpiEnabled':True}
        if c.module=='shinya_child_control':
            next(u for u in self.users if u['uid']==c.data['uid'])['delayedBlock']=c.data['cutoff']
            return {'saved':True}
        if c.module=='child_guard':
            self.writes.append(c)
            if c.action=='add':self.users=[u for u in self.users if u['uid']!=c.data['data']['uid']]+[copy.deepcopy(c.data['data'])]
            if c.action=='del':self.users=[u for u in self.users if u['uid'] not in c.data['list']]
            return {}
        raise AssertionError(c)

class ChildrenTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.store=ProjectStore(Path(self.temp.name)/'project.sqlite')
        self.keys=[DeviceKey('t','BE72','S72'),DeviceKey('t','BE50','S50')]
        self.registry=DeviceRegistry(self.store)
        for k in self.keys:
            self.store.add_project(k.tenant,k.project,k.project)
            self.store.bind_device(k.tenant,k.project,k.sn,{'sn':k.sn,'isMain':True})
            self.registry.register(k,Driver())
        self.service=ChildrenService(self.store,self.registry,AppCatalog())
        for k in self.keys:self.service.refresh_config(k)
        self.project=self.store.projects('t')[0]
    def tearDown(self):self.registry.close();self.temp.cleanup()
    def test_real_minutes_retry_and_project_isolation(self):
        raw=json.loads(Path('test/_analysis/oct11-children/be72.json').read_text(encoding='utf8'))['minute']
        body={k:raw[k] for k in ('appMinutes','deviceMinutes','traffic')};body['generatedAt']=raw['lastSample']
        db=self.service.usage(self.keys[0]);db.ingest_v3(body,router='S72')
        expected=db.report([MAC],'2026-10-11',router='S72')
        db.ingest_v3(body,router='S72')
        actual=db.report([MAC],'2026-10-11',router='S72')
        expected.pop('generatedAt',None);actual.pop('generatedAt',None)
        self.assertEqual(expected,actual)
        self.assertTrue(actual['hasData'])
        other=self.service.usage(self.keys[1]).report([MAC],'2026-10-11',router='S50')
        self.assertFalse(other['hasData'])
        report=self.service.read('netguard/onlineReport/getStaAppReport',self.keys[0],self.project,{'staId':UID,'dayDate':'2026-10-11'})
        self.assertEqual(sum(r['totalMinutes'] for r in report['rows']),sum(a['minutes'] for a in actual['apps']))
        self.assertTrue(all(isinstance(t,str) for r in report['rows'] for t in r['timeList']))
        print(json.dumps({'realPC':{'minutes':actual['onlineMinutes'],'night':actual['lateNightMinutes'],'apps':[(r['appName'],r['totalMinutes'],len(r['timeList'])) for r in report['rows']]}}),flush=True)
    def test_week_cross_midnight_complement(self):
        p={'id':'A'*24,'startTime':'22:00','endTime':'06:00','weekRepeat':'7','enableApps':''}
        native=native_policies([p],'B'*24)
        self.assertEqual(windows([p])['sun'][0][:2],(1320,1440))
        self.assertEqual(windows([p])['mon'][0][:2],(0,360))
        ban=native[0]['tr']
        self.assertEqual(ban['sun'],[['00:00','22:00']])
        self.assertEqual(ban['mon'],[['06:00','23:59']])
        self.assertEqual(ban['tue'],[['00:00','23:59']])
    def test_original_daily_chart_and_alarm_contracts(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        key=self.keys[0];user=self.service.user(key,{'staId':UID})
        epoch=int(datetime(2026,10,3,tzinfo=ZoneInfo('Asia/Shanghai')).timestamp())
        minutes=list(range(epoch,epoch+8*60,60))
        # Two apps overlap. The reused algorithm must count eight device
        # minutes, not sixteen, and the original red event card uses that union.
        self.service.usage(key).ingest_v3({'generatedAt':epoch+600,
            'deviceMinutes':[{'date':'2026-10-03','mac':MAC,'minutes':minutes}],
            'appMinutes':[{'date':'2026-10-03','mac':MAC,'app':app,'minutes':minutes} for app in ('抖音系列','微信')],
            'traffic':[]},router=key.sn)
        params={'staId':UID,'startDay':'2026-10-02','endDay':'2026-10-11'}
        daily=self.service.read('netguard/onlineReport/getStaOnlineTimeDistPerDay',key,self.project,params)
        self.assertEqual(daily['weekList'],['5','6','7','1','2','3','4','5','6','7'])
        self.assertEqual(len(daily['timeList']),10)
        self.assertEqual(daily['timeList'][1],8)
        alarms=self.service.read('netguard/onlineReport/getAlarmEventLogsBySta',key,self.project,params)['rows']
        self.assertEqual([r['day'] for r in alarms],['2026-10-'+str(d).zfill(2) for d in range(11,1,-1)])
        event=alarms[8]['eventList'][0]
        self.assertEqual(event['level'],3)
        self.assertTrue(event['detail'].startswith('【深夜上网】累计8分钟：\n00:00-00:08 |包括'))
        self.assertEqual(alarms[0]['eventList'],[{'level':0,'detail':'一切正常','params':[]}])
        other=self.service.read('netguard/onlineReport/getAlarmEventLogsBySta',self.keys[1],self.project,params)['rows']
        self.assertTrue(all(r['eventList'][0]['level']==0 for r in other))
    def test_unconfigured_and_explicit_empty_plan_states(self):
        key=self.keys[0];raw={'staId':UID}
        self.assertEqual(self.service.read('netguard/staGuard/getAppTimePlanDisable',key,self.project,raw)['disable'],-1)
        self.service.write('netguard/staGuard/setAppTimePlanDisable',key,self.project,{**raw,'disable':0})
        self.assertEqual(self.service.read('netguard/staGuard/getAppTimePlanDisable',key,self.project,raw)['disable'],0)
        self.service.write('netguard/staGuard/addAppTimePlan',key,self.project,{**raw,'startTime':'08:00','endTime':'20:00','weekRepeat':'1','enableApps':''})
        week=self.service.read('netguard/staGuard/getAppTimePlansByWeek',key,self.project,{**raw,'week':'1'})
        self.assertEqual(len(week['appTimePlanList']),1)
        self.assertEqual(week['banTimeSpanList'],['00:00-08:00','20:00-23:59'])
        self.assertEqual(week['disable'],0)
    def test_supplemental_icon_uri_works_with_original_fastimage(self):
        catalog=AppCatalog()
        self.assertTrue(all(r['link'].startswith(('file:///android_asset/hub-appicons/',
            'https://shinyaapp.ruijie.com.cn/assets/icons/')) for r in catalog.extra))
    def test_reused_icons_serve_from_own_hub_and_catalog_stays_scoped(self):
        import hashlib
        from shinya_hub.assets import PublicIconStore
        from shinya_hub.catalog import RESOURCE
        catalog=AppCatalog();key=self.keys[0]
        expected={'米家','美的美居','TP-LINK物联','海尔智家','小爱同学','淘宝','阿里CDN'}
        icons={r['showName']:r for r in catalog.extra if r['showName'] in expected}
        self.assertEqual(set(icons),expected)
        self.assertNotIn('美的美居',{r['showName'] for r in catalog.for_project(self.store,key.tenant,self.project)['rows']})
        self.store.save(key.tenant,key.project,key.sn,RESOURCE,{'ids':['9-212-1-0']})
        self.assertIn('美的美居',{r['showName'] for r in catalog.for_project(self.store,key.tenant,self.project)['rows']})
        other=next(p for p in self.store.projects(key.tenant) if p['projectId']==self.keys[1].project)
        self.assertNotIn('美的美居',{r['showName'] for r in catalog.for_project(self.store,key.tenant,other)['rows']})
        app=create_app(self.store,self.registry,{token_digest('test'):Principal('t','Demon',True)},
            catalog=catalog,icons=PublicIconStore(Path(self.temp.name)/'icons'))
        try:
            for row in icons.values():
                if '/assets/icons/' not in row['link']:continue
                digest=row['link'].rsplit('/',1)[-1]
                response=app.test_client().get('/assets/icons/'+digest)
                self.assertEqual(response.status_code,200)
                self.assertEqual(response.content_type,'image/png')
                self.assertEqual(hashlib.sha256(response.data).hexdigest(),digest)
            self.assertEqual(app.test_client().get('/assets/icons/'+'0'*64).status_code,404)
        finally:app.extensions['shinya_diagnosis'].close()
    def test_report_icons_match_verified_firmware_names(self):
        catalog={r['showName']:r for r in AppCatalog().official}
        douyin=self.service.report_app('抖音',catalog)
        self.assertEqual(douyin['id'],'10-5-1-0')
        self.assertEqual(douyin,catalog['抖音系列'])
        self.assertEqual(self.service.report_app('快手',catalog),catalog['快手系列'])
        taobao=self.service.report_app('淘宝',catalog)
        self.assertEqual(taobao['id'],'18-4-2-0')
        self.assertTrue(taobao['link'].endswith('/taobao.png'))
        self.assertEqual(self.service.report_app('陌生应用',catalog),{})
    def test_plan_crud_and_conflict_and_switch(self):
        key=self.keys[0];base={'staId':UID,'startTime':'08:00','endTime':'20:00','weekRepeat':'1,2,3,4,5,6,7','enableApps':''}
        self.service.write('netguard/staGuard/addAppTimePlan',key,self.project,base)
        plans=self.service.read('netguard/staGuard/getAppTimePlans',key,self.project,{'staId':UID})['rows']
        self.assertEqual(len(plans),1)
        pid=plans[0]['id']
        with self.assertRaises(ChildError):self.service.write('netguard/staGuard/addAppTimePlan',key,self.project,base)
        self.service.write('netguard/staGuard/editAppTimePlan',key,self.project,{**base,'id':pid,'endTime':'21:00'})
        self.service.write('netguard/staGuard/setAppTimePlanDisable',key,self.project,{'staId':UID,'disable':1})
        self.assertEqual(self.service.user(key,base)['policies'],[])
        self.service.write('netguard/staGuard/setAppTimePlanDisable',key,self.project,{'staId':UID,'disable':0})
        self.assertTrue(self.service.user(key,base)['policies'])
        self.service.write('netguard/staGuard/deleteAppTimePlan',key,self.project,{'staId':UID,'id':pid})
        self.assertEqual(self.service.user(key,base)['policies'],[])
    def test_foreign_uid_and_mac_rejected_before_write(self):
        with self.assertRaises(ChildError):self.service.user(self.keys[0],{'staId':UID,'staMac':'AA1122334455'})
        with self.assertRaises(ChildError):self.service.user(self.keys[0],{'staId':'F'*32})
    def test_manual_immediate_and_delayed_have_distinct_native_meaning(self):
        import time
        key=self.keys[0]
        self.service.write('netguard/staGuard/setUsrOpt',key,self.project,{'staId':UID,'usrOpt':2,'cutoffTimeSec':int(time.time())-60})
        self.assertEqual(self.service.user(key,{'staId':UID})['block'],-1)
        self.service.write('netguard/staGuard/setUsrOpt',key,self.project,{'staId':UID,'usrOpt':2,'cutoffTimeSec':int(time.time())+600})
        user=self.service.user(key,{'staId':UID})
        self.assertEqual(user['block'],0)
        self.assertGreater(user['delayedBlock'],int(time.time()))
        self.service.write('netguard/staGuard/setUsrOpt',key,self.project,{'staId':UID,'usrOpt':0})
        self.assertEqual(self.service.user(key,{'staId':UID})['delayedBlock'],0)
    def test_http_ownership_and_write_gate(self):
        app=create_app(self.store,self.registry,{token_digest('test'):Principal('t','Demon',True)},enabled_child_writes={('t','BE72')},catalog=AppCatalog())
        svc=app.extensions['shinya_children']
        for key in self.keys:svc.refresh_config(key)
        c=app.test_client();headers={'Authorization':'Bearer test'}
        self.assertEqual(c.get('/netguard/staGuard/getProtectMenus?mainApSn=foreign',headers=headers).json['code'],404)
        body={'mainApSn':'S50','staId':UID,'disable':1}
        self.assertEqual(c.post('/netguard/staGuard/setAppTimePlanDisable',json=body,headers=headers).json['code'],403)
        self.assertEqual(c.post('/netguard/staGuard/setAppTimePlanDisable',json=body).json['code'],403)
        app.extensions['shinya_diagnosis'].close()
    def test_project_list_aliases_resolve_owned_child_pages(self):
        app=create_app(self.store,self.registry,{token_digest('test'):Principal('t','Demon',True)},catalog=AppCatalog())
        svc=app.extensions['shinya_children']
        for key in self.keys:svc.refresh_config(key)
        c=app.test_client();headers={'Authorization':'Bearer test'}
        result=c.post('/homewlan/apMonitor/getProjectList2',json={},headers=headers).json
        self.assertEqual(result['code'],0)
        for row,key in zip(result['data']['dataList'],self.keys):
            self.assertTrue(str(row['projectId']).isdigit())
            self.assertEqual(row['apList'][0]['projectId'],row['projectId'])
            self.assertEqual(self.store.project_for_alias('t',row['projectId']),key.project)
            child=c.get('/netguard/staGuard/getProtectMenus',query_string={'projectId':row['projectId']},headers=headers).json
            self.assertEqual(child['code'],0)
        app.extensions['shinya_diagnosis'].close()

if __name__=='__main__':unittest.main()
