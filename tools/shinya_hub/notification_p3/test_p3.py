import contextlib
import itertools
import json
import sqlite3
import tempfile
import unittest
from types import SimpleNamespace

from shinya_hub.assistant_notifications import AssistantNotifications
from shinya_hub.notification_rules import CATEGORIES, merge_rule


class Tests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        path=self.temp.name+'/test.db'
        class Store:
            def connect(self):
                db=sqlite3.connect(path);db.row_factory=sqlite3.Row;return db
            def atomic(self):return contextlib.nullcontext()
        self.store=Store();self.key=SimpleNamespace(tenant='owner',project='home',sn='router')
        self.engine=AssistantNotifications(self.store,catalog_rows=[
            {'id':'social','type':'0005','showName':'社交应用'},
            {'id':'game','type':'0003','showName':'游戏应用'},
            {'id':'video','type':'0002','showName':'视频应用'},
            {'id':'18-1-1-0','type':'0006','showName':'微信支付'}])
        self.now=1793502000//60*60

    def test_child_report_verified_alias_is_shared(self):
        self.engine.by_id['douyin']={'id':'douyin','type':'0002','showName':'抖音系列'}
        self.engine.by_name['抖音系列']=self.engine.by_id['douyin']
        self.assertEqual(self.engine._app({'app':'抖音'})['id'],'douyin')
        self.assertIsNone(self.engine._app({'appId':'unknown','app':'抖音'}))

    def test_default_catalog_reuses_child_supplemental_entries(self):
        from shinya_hub.catalog import AppCatalog
        actual=AssistantNotifications(self.store)
        for row in AppCatalog().extra:
            self.assertEqual(actual._app({'appId':row['id']})['id'],row['id'])

    def test_p2_draft_partial_save_and_cancel_preserve_other_categories(self):
        old=self.rule(game=True,video=True)
        configured=merge_rule(old,{'settings':{'game':{'minutes':45,'allApps':False,'appIds':[]}}},self.now+1)
        self.assertEqual(configured['settings']['game']['minutes'],45)
        self.assertEqual(configured['settings']['video']['minutes'],10)
        self.assertEqual(old['settings']['game']['minutes'],10)
        self.assertEqual(configured['categoryUpdatedAt']['video'],old['categoryUpdatedAt']['video'])
        self.assertGreater(configured['categoryUpdatedAt']['game'],old['categoryUpdatedAt']['game'])

    def test_p2_invalid_thresholds_and_categories_are_rejected(self):
        from shinya_hub.notification_preferences import configuration
        for n in [0,1441,True,1.5,'30',None]:
            with self.assertRaises(ValueError):configuration(updates={'game':{'minutes':n}})
        for n in [1,1440]:self.assertEqual(configuration(updates={'game':{'minutes':n}})['game']['minutes'],n)
        with self.assertRaises(ValueError):configuration(updates={'other':{}})
        with self.assertRaises(ValueError):configuration(updates={'payment':{'minutes':10}})
        with self.assertRaises(ValueError):merge_rule({}, {'settings':{'game':{'appIds':['unknown']}}},self.now)

    def test_p2_selected_apps_and_custom_threshold_are_used(self):
        from shinya_hub.notification_preferences import configuration
        rule=self.rule(game=True);rule['settings']=configuration(updates={'game':{'minutes':20}})
        self.detect(rule,self.report('game',self.now-600,self.now),self.now)
        self.assertFalse(self.detect(rule,self.report('game',self.now,self.now+600),self.now+600))
        emitted=self.detect(rule,self.report('game',self.now,self.now+1200),self.now+1200)
        self.assertEqual(len(emitted),1)
        rule['settings']['game']['allApps']=False;rule['settings']['game']['appIds']=[]
        self.assertFalse(self.detect(rule,self.report('game',self.now+1800,self.now+3600),self.now+3600))

    def test_p2_disable_duration_keeps_independent_switch(self):
        from shinya_hub.notification_preferences import configuration
        rule=self.rule(social=True);rule['settings']=configuration(updates={'social':{'durationEnabled':False}})
        self.detect(rule,self.report('social',self.now-600,self.now),self.now)
        self.assertFalse(self.detect(rule,self.report('social',self.now,self.now+3600),self.now+3600))
        self.assertTrue(rule['social'])

    def test_p3_separate_episodes_are_not_recombined_by_old_daily_ranges(self):
        rule=self.rule(game=True)
        self.detect(rule,self.report('game',self.now-600,self.now),self.now)
        self.assertEqual(len(self.detect(rule,self.report('game',self.now,self.now+600),self.now+600)),1)
        report=self.report('game',self.now,self.now+600)
        report['apps'][0]['sessionRanges'].append({'startEpoch':self.now+1200,'endEpoch':self.now+1500,'activeSeconds':300})
        self.assertFalse(self.detect(rule,report,self.now+1500))
        report['apps'][0]['sessionRanges'][1].update(endEpoch=self.now+1800,activeSeconds=600)
        self.assertEqual(len(self.detect(rule,report,self.now+1800)),1)
        self.assertFalse(self.detect(rule,report,self.now+1800))
        self.assertEqual(len([e for e in self.engine.feed(self.key) if e.get('recordKind')=='reminder']),2)

    def test_p3_device_start_long_and_end_require_real_healthy_observation(self):
        from shinya_hub.notification_preferences import configuration
        rule=self.rule(enable=True);rule['settings']=configuration(updates={'enable':{'night':False,'longMinutes':10}})
        self.detect(rule,self.report('game',self.now-600,self.now),self.now)
        all_events=[]
        for minute in range(1,11):all_events+=self.detect(rule,self.report('game',self.now,self.now+minute*60),self.now+minute*60)
        self.assertEqual([e['ruleKind'] for e in all_events],['start','long'])
        for minute in range(11,21):all_events+=self.detect(rule,self.report('game',self.now,self.now+600),self.now+minute*60)
        self.assertEqual([e['ruleKind'] for e in all_events],['start','long','end'])
        self.assertFalse(self.detect(rule,self.report('game',self.now,self.now+600),self.now+1260))

    def test_p3_outage_does_not_become_end_reminder(self):
        from shinya_hub.notification_preferences import configuration
        rule=self.rule(enable=True);rule['settings']=configuration(updates={'enable':{'night':False}})
        self.detect(rule,self.report('game',self.now-60,self.now),self.now)
        self.detect(rule,self.report('game',self.now,self.now+300),self.now+300)
        report=self.report('game',self.now,self.now+300);report['stale']=True
        self.assertFalse(self.detect(rule,report,self.now+1200))
        report['stale']=False
        for minute in range(21,33):self.assertFalse(self.detect(rule,report,self.now+minute*60))

    def test_p3_beijing_night_boundary(self):
        from shinya_hub.notification_trigger_rules import night
        from shinya_hub.daily import CHINA
        from datetime import datetime
        stamp=lambda h,m:datetime(2026,11,1,h,m,tzinfo=CHINA).timestamp()
        self.assertFalse(night([[stamp(6,0),stamp(6,1)]]))
        self.assertTrue(night([[stamp(5,59),stamp(6,0)]]))
        self.assertFalse(night([[stamp(21,59),stamp(22,0)]]))
        self.assertTrue(night([[stamp(22,0),stamp(22,1)]]))

    def test_p3_midnight_preserves_one_episode_and_default_long_threshold(self):
        from datetime import datetime
        from shinya_hub.daily import CHINA
        self.now=int(datetime(2026,11,1,21,30,tzinfo=CHINA).timestamp())
        rule=self.rule(enable=True)
        self.detect(rule,{},self.now)
        events=[]
        for minute in range(1,181):
            # At midnight the source changes to a new natural-day range.
            start=self.now if minute<=150 else self.now+150*60
            events+=self.detect(rule,self.report('social',start,self.now+minute*60),self.now+minute*60)
        kinds=[e['ruleKind'] for e in events]
        self.assertEqual(kinds.count('start'),1)
        self.assertEqual(kinds.count('night'),1)
        self.assertEqual(kinds.count('long'),1)
        self.assertEqual(next(e['triggeredAt'] for e in events if e['ruleKind']=='long'),(self.now+120*60)*1000)

    def test_p3_scope_and_payment_creation_use_actual_access(self):
        rule=self.rule(payment=True)
        self.detect(rule,{},self.now)
        event=self.detect(rule,self.report('18-1-1-0',self.now+60,self.now+120),self.now+120)[0]
        self.assertEqual(event['createTime'],(self.now+60)*1000)
        for tenant,project,sn in [('other','home','router'),('owner','different','router'),('owner','home','different')]:
            other=SimpleNamespace(tenant=tenant,project=project,sn=sn)
            self.assertFalse(self.engine.feed(other))

    def test_p3_rule_change_does_not_backfill(self):
        rule=self.rule(social=True)
        self.detect(rule,self.report('social',self.now-3600,self.now),self.now)
        changed=merge_rule(rule,{'settings':{'social':{'minutes':1}}},self.now+60)
        self.assertFalse(self.detect(changed,self.report('social',self.now-3600,self.now+60),self.now+60))
        self.assertEqual(len(self.detect(changed,self.report('social',self.now-3600,self.now+120),self.now+120)),1)

    def test_p3_long_poll_wakes_and_validates_bounds(self):
        from shinya_hub.notification_wait import wait_for_feed,changed
        import threading,time
        state=[];out=[]
        thread=threading.Thread(target=lambda:out.append(wait_for_feed(lambda:{'items':list(state)},2)))
        thread.start();time.sleep(.05);state.append({'id':1});changed();thread.join(1)
        self.assertFalse(thread.is_alive());self.assertEqual(out[0]['items'],[{'id':1}])
        for bad in [-1,16,True,1.5]:
            with self.assertRaises(ValueError):wait_for_feed(lambda:{'items':[]},bad)

    def test_p3_non_guard_report_identity_resolves_notification_click(self):
        from unittest.mock import patch
        from shinya_hub.xiaoxing import XiaoxingService
        service=XiaoxingService.__new__(XiaoxingService);service.store=self.store
        service._load=lambda key,name,default:default
        service.children=SimpleNamespace(config=lambda key:{'users':[]},station_report_id=lambda key,mac:'A'*32)
        station={'mac':'aa:bb:cc:dd:ee:ff','type':'phone','remark':'手机','apSn':self.key.sn}
        with patch('shinya_hub.xiaoxing.station_snapshots',return_value=[station]):
            result=service.get_notify_rule(self.key,{},'A'*32)
            self.assertEqual(result['mac'],'AABBCCDDEEFF')
            self.assertFalse(result['isChildGuarded'])
            self.assertEqual(service.get_notify_rule(self.key,{},'B'*32)['mac'],'')

    def tearDown(self):self.temp.cleanup()

    def rule(self,**values):return merge_rule({},values,self.now)

    def report(self,app,start,end):
        return {'basis':'minutes','coverage':{'hasRecords':True},'apps':[
            {'appId':app,'sessionRanges':[{'startEpoch':start,'endEpoch':end,'activeSeconds':end-start}]}]}

    def detect(self,rule,report,at):
        return self.engine.detect(self.key,'user:one','手机',rule,report,
            observed_at=at,online=True,now=at)

    def test_all_32_combinations_and_partial_updates(self):
        for values in itertools.product((False,True),repeat=5):
            old=merge_rule({},dict(zip(CATEGORIES,values)),self.now)
            for name in CATEGORIES:
                new=merge_rule(old,{name:not old[name]},self.now+60)
                self.assertEqual([new[k] for k in CATEGORIES if k!=name],[old[k] for k in CATEGORIES if k!=name])
                self.assertEqual(new['categoryUpdatedAt']['game'] if name!='game' else old['categoryUpdatedAt']['game'],old['categoryUpdatedAt']['game'])
        with self.assertRaises(ValueError):merge_rule({}, {'social':1},self.now)

    def test_migration_preserves_four_values_and_social_off(self):
        rule=merge_rule({'enable':False,'game':True,'video':False,'payment':True,'updatedAt':self.now},{},self.now+10)
        self.assertFalse(rule['enable']);self.assertTrue(rule['game']);self.assertTrue(rule['payment']);self.assertFalse(rule['social'])

    def test_game_independent_one_reminder_and_activity_revision(self):
        rule=self.rule(game=True,enable=False)
        self.detect(rule,self.report('game',self.now-60,self.now),self.now)
        result=[]
        for minute in range(1,12): result+=self.detect(rule,self.report('game',self.now,self.now+minute*60),self.now+minute*60)
        self.assertEqual(len(result),1);self.assertEqual(result[0]['category'],'game')
        again=self.detect(rule,self.report('game',self.now,self.now+11*60),self.now+11*60)
        self.assertEqual(again,[])
        feed=self.engine.feed(self.key)
        self.assertTrue(any(row.get('recordKind')=='activity' for row in feed))
        self.assertFalse(any(row.get('recordKind')=='reminder' and row['category']=='usage' for row in feed))

    def test_social_has_30_minute_threshold(self):
        rule=self.rule(social=True)
        self.detect(rule,self.report('social',self.now-60,self.now),self.now)
        emitted=[]
        for minute in range(1,32):
            current=self.detect(rule,self.report('social',self.now,self.now+minute*60),self.now+minute*60)
            if minute<30:self.assertFalse(current)
            emitted+=current
        self.assertEqual(len(emitted),1);self.assertEqual(emitted[0]['appId'],'social')

    def test_payment_not_generic_usage_and_no_completed_payment(self):
        rule=self.rule(payment=True)
        self.detect(rule,{},self.now)
        report=self.report('18-1-1-0',self.now+60,self.now+120)
        events=self.detect(rule,report,self.now+120)
        self.assertEqual(len(events),1);self.assertEqual(events[0]['category'],'payment')
        self.assertIn('不代表已完成付款',events[0]['content'])
        self.assertEqual(events[0]['accessTime'],(self.now+60)*1000)

    def test_unknown_id_cannot_gain_classification_through_name(self):
        report=self.report('missing',self.now,self.now+60);report['apps'][0]['app']='微信支付'
        rule=self.rule(payment=True);self.detect(rule,{},self.now)
        self.assertEqual(self.detect(rule,report,self.now+60),[])

    def test_aggregate_range_not_single_event_end(self):
        rule=self.rule(enable=True);report=self.report('social',self.now,self.now+600)
        report['apps'][0]['sessionRanges'][0]['activeSeconds']=60
        self.detect(rule,report,self.now+600)
        feed=self.engine.feed(self.key)
        activity=[row for row in feed if row.get('recordKind')=='activity'][-1]
        self.assertEqual(activity['timeKind'],'observedRange')
        self.assertIsNone(activity['apps'][0]['sessionRanges'][0]['endTime'])
        self.assertFalse(any(row.get('recordKind')=='reminder' for row in feed))

    def test_disabled_or_stale_data_does_not_generate_reminder(self):
        rule=self.rule(game=True);self.detect(rule,{},self.now)
        report=self.report('game',self.now,self.now+660);report['stale']=True
        self.assertEqual(self.detect(rule,report,self.now+660),[])
        self.assertEqual(self.engine.feed(SimpleNamespace(tenant='other',project='home',sn='router')),[])

    def test_service_partial_rule_save_preserves_other_categories(self):
        import threading
        from unittest.mock import patch
        from shinya_hub.xiaoxing import XiaoxingService
        service=XiaoxingService.__new__(XiaoxingService)
        service.children=None;service.store=self.store;service._lock=threading.RLock()
        state={}
        service._load=lambda key,name,default:state.get(name,default)
        service._save=lambda key,name,value:state.update({name:value})
        service.get_notify_rule=lambda *args: next(value for value in state.values() if isinstance(value,dict) and 'aa:bb:cc:dd:ee:ff' in value)['aa:bb:cc:dd:ee:ff']
        station={'mac':'aa:bb:cc:dd:ee:ff','type':'phone','remark':'手机'}
        with patch('shinya_hub.xiaoxing.station_snapshots',return_value=[station]):
            first=service.set_notify_rule(self.key,{},dict(mac=station['mac'],game=True,payment=True))
            second=service.set_notify_rule(self.key,{},dict(mac=station['mac'],social=True,enable=False))
        self.assertTrue(second['game']);self.assertTrue(second['payment']);self.assertTrue(second['social'])
        self.assertFalse(second['enable']);self.assertEqual(first['categoryUpdatedAt']['game'],second['categoryUpdatedAt']['game'])

    def test_exact_source_session_updates_same_activity(self):
        rule=self.rule(payment=True)
        report=self.report('18-1-1-0',self.now,self.now+60)
        session=report['apps'][0]['sessionRanges'][0];session['sessionId']='real-source-event'
        self.detect(rule,report,self.now+60)
        session.update(endEpoch=self.now+120,activeSeconds=120,endConfirmed=True)
        self.detect(rule,report,self.now+120)
        with self.store.connect() as db:
            rows=db.execute("SELECT payload FROM assistant_activity_events WHERE category='payment'").fetchall()
        activities=[json.loads(row['payload']) for row in rows if json.loads(row['payload']).get('recordKind')=='activity']
        self.assertEqual(len(activities),1)
        self.assertEqual(activities[0]['apps'][0]['sessionRanges'][0]['endTime'],(self.now+120)*1000)

    def test_history_refresh_does_not_reset_active_rule_state(self):
        from shinya_hub.notification_activity import detect
        self.engine.payment_ids=frozenset(('18-1-1-0',))
        rule=self.rule(game=True);self.detect(rule,{},self.now)
        for minute in range(1,6):self.detect(rule,self.report('game',self.now,self.now+minute*60),self.now+minute*60)
        with self.store.connect() as db:before=[tuple(row) for row in db.execute('SELECT * FROM assistant_activity_baselines')]
        detect(self.engine,self.key,'user:one','手机',rule,self.report('game',self.now-1200,self.now-600),
               observed_at=self.now-600,online=False,now=self.now+300,history_only=True)
        with self.store.connect() as db:after=[tuple(row) for row in db.execute('SELECT * FROM assistant_activity_baselines')]
        self.assertEqual(before,after)


if __name__=='__main__':unittest.main(verbosity=2)
