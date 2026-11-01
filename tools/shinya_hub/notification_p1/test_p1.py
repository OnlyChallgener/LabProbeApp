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
