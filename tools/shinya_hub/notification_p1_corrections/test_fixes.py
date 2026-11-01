import json, unittest, sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'notification_p3'))
import test_p3

class Tests(test_p3.Tests):
    def test_end_events_keep_distinct_sessions_and_have_observed_duration(self):
        rule=self.rule(enable=True)
        self.detect(rule,{},self.now)
        report=self.report('game',self.now,self.now+300)
        events=[]
        for minute in range(1,16):
            if minute<=5:report=self.report('game',self.now,self.now+minute*60)
            events+=self.detect(rule,report,self.now+minute*60)
        ended=[e for e in events if e['ruleKind']=='end']
        self.assertEqual(len(ended),1)
        one=ended[0]
        self.assertEqual(one['activeSeconds'],300)
        self.assertEqual(one['endTime'],(self.now+300)*1000)
        self.assertIn('用网时段',one['content']);self.assertIn('5 分钟',one['content'])
        self.assertNotIn('10分钟未',one['content'])
        for minute in range(16,21):self.assertFalse(self.detect(rule,report,self.now+minute*60))
        # Retain the original daily range while another real episode is collected.
        for minute in range(21,36):
            latest=self.report('game',self.now+1200,self.now+min(minute,25)*60)
            report['apps'][0]['sessionRanges']=[self.report('game',self.now,self.now+300)['apps'][0]['sessionRanges'][0],latest['apps'][0]['sessionRanges'][0]]
            events+=self.detect(rule,report,self.now+minute*60)
        ended=[e for e in events if e['ruleKind']=='end']
        self.assertEqual(len(ended),2)
        self.assertNotEqual(ended[0]['sessionId'],ended[1]['sessionId'])
        self.assertEqual(len({e['eventKey'] for e in ended}),2)

    def test_legacy_end_content_revision_is_not_new_reminder(self):
        from shinya_hub.notification_activity import save
        from shinya_hub.notification_trigger_rules import revise_ended
        with self.store.connect() as db:
            old=save(self.engine,db,self.key,'user:one','reminder','usage','old-token',
                     {'ruleKind':'end','startTime':self.now*1000,'lastActivityTime':(self.now+60)*1000,'triggeredAt':(self.now+660)*1000,'content':'连续10分钟未检测到有效网络活动'},self.now+660)
            revise_ended(self.engine,db,self.key,'user:one',self.now+700)
            count=db.execute('select count(*) from assistant_activity_changes').fetchone()[0]
            revise_ended(self.engine,db,self.key,'user:one',self.now+701)
            self.assertEqual(db.execute('select count(*) from assistant_activity_changes').fetchone()[0],count)
            new=json.loads(db.execute("select payload from assistant_activity_events where event_key='old-token'").fetchone()[0])
        self.assertFalse(new['newReminder']);self.assertEqual(new['createTime'],old['createTime'])
        self.assertIn('用网时段',new['content'])

    def test_recent_range_is_not_ongoing_without_source_evidence(self):
        rule=self.rule(video=True)
        self.detect(rule,self.report('video',self.now,self.now+60),self.now+60)
        activity=next(e for e in self.engine.feed(self.key) if e['recordKind']=='activity')
        self.assertFalse(activity['ongoing'])

    def test_confirmed_end_cannot_be_reopened_by_incomplete_refresh(self):
        rule=self.rule(video=True);report=self.report('video',self.now,self.now+60)
        sample=report['apps'][0]['sessionRanges'][0]
        sample.update(sessionId='one-real-session',ongoing=True)
        self.detect(rule,report,self.now+60)
        sample.update(endEpoch=self.now+120,activeSeconds=120,endConfirmed=True)
        self.detect(rule,report,self.now+120)
        sample.pop('endConfirmed')
        self.detect(rule,report,self.now+121)
        with self.store.connect() as db:
            activity=next(json.loads(e[0]) for e in db.execute('select payload from assistant_activity_events') if json.loads(e[0])['recordKind']=='activity')
        self.assertFalse(activity['ongoing']);self.assertTrue(activity['endConfirmed'])
        self.assertEqual(activity['apps'][0]['sessionRanges'][0]['endTime'],(self.now+120)*1000)

    def test_disabling_game_does_not_disable_video(self):
        rule=self.rule(game=True,video=True)
        self.detect(rule,{},self.now)
        from shinya_hub.notification_rules import merge_rule
        rule=merge_rule(rule,{'game':False},self.now+60)
        events=[]
        for minute in range(1,13):
            report=self.report('game',self.now,self.now+minute*60)
            report['apps']+=self.report('video',self.now,self.now+minute*60)['apps']
            events+=self.detect(rule,report,self.now+minute*60)
        self.assertTrue(any(e['category']=='video' for e in events))
        self.assertFalse(any(e['category']=='game' for e in events))

if __name__=='__main__':unittest.main(verbosity=2)
