"""Activity snapshots and distinct reminder events over existing child reports.

Minute ranges are observed activity ranges, not precise foreground sessions.
No screen activity, payment completion or missing intervals are inferred.
"""
import hashlib
import json
import math
from datetime import datetime, timedelta

from .daily import CHINA

LABELS = {'usage': '设备开始用网', 'game': '游戏提醒', 'video': '视频提醒',
          'payment': '支付类访问提醒', 'social': '社交提醒'}
TYPES = {'0003': 'game', '0002': 'video', '0005': 'social'}


def number(value):
    if isinstance(value, bool): return None
    try: result = float(value)
    except (TypeError, ValueError, OverflowError): return None
    return result if math.isfinite(result) and result >= 0 else None


def identity(*parts):
    return 'usage-v2-' + hashlib.sha256(json.dumps(parts, ensure_ascii=False).encode()).hexdigest()


def union(intervals):
    result = []
    for start, end in sorted(intervals):
        if end <= start: continue
        if result and start <= result[-1][1]: result[-1][1] = max(end, result[-1][1])
        else: result.append([start, end])
    return result


def save(engine, db, key, device, kind, category, token, payload, now):
    old = db.execute('SELECT payload FROM assistant_activity_events WHERE event_key=?', (token,)).fetchone()
    old = json.loads(old['payload']) if old else None
    value = {**payload, 'eventId': token, 'eventKey': token, 'recordKind': kind,
             'category': category, 'projectScope': key.project, 'sn': key.sn,
             'deviceId': device, 'schemaVersion': 2}
    if old and all(old.get(name) == val for name, val in value.items()): return None
    created = (payload.get('startTime', int(now*1000))/1000) if kind == 'activity' else now
    value.update(createTime=old.get('createTime') if old else int(created*1000),
                 updatedAt=int(now*1000), newReminder=kind == 'reminder' and old is None)
    cur = db.execute('INSERT INTO assistant_activity_changes(tenant,project,sn,event_key,payload) VALUES(?,?,?,?,?)',
                     (key.tenant,key.project,key.sn,token,'{}'))
    value.update(id=cur.lastrowid, revisionSeq=cur.lastrowid)
    text = json.dumps(value, ensure_ascii=False)
    db.execute('UPDATE assistant_activity_changes SET payload=? WHERE seq=?', (text,cur.lastrowid))
    db.execute('''INSERT INTO assistant_activity_events(event_key,tenant,project,sn,device,category,created,payload)
        VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(event_key) DO UPDATE SET payload=excluded.payload''',
        (token,key.tenant,key.project,key.sn,device,category,created,text))
    return value


def detect(engine, key, device, name, rule, report, *, observed_at, online, now, project_id=None, **unused):
    engine._scope(key)
    if not isinstance(device,str) or not device or len(device)>256: raise ValueError('设备标识无效')
    now = number(now); observed = number(observed_at)
    if now is None: raise ValueError('观察时间无效')
    rule = rule if isinstance(rule,dict) else {}; report = report if isinstance(report,dict) else {}
    fresh = (online is True and observed is not None and 0 <= now-observed <= engine.max_age
             and report.get('stale') is not True and (report.get('coverage') or {}).get('hasRecords') is True)
    cutoff = (datetime.fromtimestamp(now,CHINA).replace(hour=0,minute=0,second=0,microsecond=0)-timedelta(days=2)).timestamp()
    ranges=[]
    for row in report.get('apps') or []:
        if not isinstance(row,dict): continue
        app_id=row.get('appId') or row.get('id') or row.get('app')
        app=engine._app(row)
        # Use the existing child catalog resolver: an explicit unknown ID never
        # falls back to a name; legacy report names need an exact unique catalog match.
        if not app or not app.get('showName'): continue
        category='payment' if app['id'] in engine.payment_ids else TYPES.get(app.get('type'))
        for session in row.get('sessionRanges') or []:
            if not isinstance(session,dict): continue
            start,end,active=(number(session.get(k)) for k in ('startEpoch','endEpoch','activeSeconds'))
            if start is None or end is None or end<=start or start<cutoff or observed is None or end>observed+60 or end>now+60: continue
            # Preserve aggregate ranges but never turn their span into usage minutes.
            minutes=number(session.get('minutes'))
            if active is None and minutes is None: continue
            if active is not None and (active<=0 or active>end-start): continue
            ranges.append({'appId':app['id'],'appName':app['showName'],'category':category,
                'start':start,'end':end,'active':active,'located':active==end-start and report.get('basis')=='minutes',
                'sourceEventId':session.get('eventId') or session.get('sessionId'),
                'endConfirmed':session.get('endConfirmed') is True})
    ranges.sort(key=lambda sample:(sample['start'],sample['end'],sample['appId']))
    emitted=[];scope=(key.tenant,key.project,key.sn)
    with engine.store.atomic(), engine.store.connect() as db:
        # Source activity and reminder delivery state use different identities.
        for sample in ranges:
            token=identity(*scope,device,'activity',sample['appId'],sample['sourceEventId'] or sample['start'])
            ongoing=not sample['endConfirmed'] and fresh and 0 <= observed-sample['end'] <= 120
            time_kind='confirmedSession' if sample['endConfirmed'] and sample['sourceEventId'] else 'observedRange'
            payload={'projectId':project_id or key.project,'deviceName':name,
                'appId':sample['appId'],'appName':sample['appName'],
                'apps':[{'appId':sample['appId'],'appName':sample['appName'],'sessionRanges':[
                    {'startTime':int(sample['start']*1000),'lastActivityTime':int(sample['end']*1000),
                     'endTime':int(sample['end']*1000) if time_kind=='confirmedSession' else None,'timeKind':time_kind}]}],
                'startTime':int(sample['start']*1000),'lastActivityTime':int(sample['end']*1000),
                'ongoing':ongoing,'timeKind':time_kind,'sourceEventId':sample['sourceEventId'],
                'source':'children.report.sessionRanges'}
            save(engine,db,key,device,'activity',sample['category'] or 'activity',token,payload,now)
        versions=rule.get('categoryUpdatedAt') or {}
        for category in (() if unused.get('history_only') else LABELS):
            rule_name='enable' if category=='usage' else category
            version=number(versions.get(rule_name)) or number(rule.get('updatedAt')) or now
            ident=(*scope,device,category)
            previous=db.execute('SELECT * FROM assistant_activity_baselines WHERE tenant=? AND project=? AND sn=? AND device=? AND category=?',ident).fetchone()
            enabled=rule.get(rule_name) is True
            saved=json.loads(previous['state']) if previous else {}
            reset=(previous is None or previous['rule_version']!=version or not previous['enabled']
                   or saved.get('schemaVersion')!=2)
            state=saved if not reset else {'schemaVersion':2}
            blocked=max(previous['blocked_before'] if previous else now,now if reset or not fresh else 0)
            if not enabled: state={'schemaVersion':2}
            elif fresh and not reset:
                candidates=[s for s in ranges if (category=='usage' and s['located']) or s['category']==category]
                for sample in candidates:
                    if sample['end']<=blocked: continue
                    app_id='' if category=='usage' else sample['appId']
                    sessions=state.setdefault('sessions',{})
                    # Payment accesses are per source interval, never generic app visits.
                    key_id=identity(app_id,sample['start']) if category=='payment' else app_id
                    current=sessions.get(key_id,{})
                    begin=max(sample['start'],math.ceil(blocked/60)*60)
                    end=min(sample['end'],math.floor(observed/60)*60)
                    if category!='payment' and (not sample['located'] or end<=begin): continue
                    if current and begin-current.get('last',begin)>=600:
                        # A new observed range is a new episode; no inferred end timestamp.
                        current={}
                    first=current.get('first',begin)
                    intervals=union(current.get('intervals',[])+[[begin,end]])
                    minutes=sum(b-a for a,b in intervals)/60
                    threshold=5 if category=='usage' else 30 if category=='social' else 10
                    token=current.get('token') or identity(*scope,device,'reminder',category,app_id,first)
                    if (category=='payment' and sample['start']>=blocked) or minutes>=threshold:
                        payload={'projectId':project_id or key.project,'deviceName':name,
                            'title':LABELS[category], 'content':('检测到支付服务访问，不代表已完成付款' if category=='payment' else '检测到设备持续网络活动' if category=='usage' else sample['appName']+'网络活动已达'+str(threshold)+'分钟'),
                            'appId':app_id,'appName':'' if category=='usage' else sample['appName'],
                            'activityId':identity(*scope,device,'activity',sample['appId'],sample['start']) if app_id else None,
                            'startTime':int(first*1000),'accessTime':int(sample['start']*1000) if category=='payment' else None,
                            'triggeredAt':current.get('triggeredAt') or int(observed*1000),
                            'source':'children.report.sessionRanges','measurementBasis':'observed-network-activity'}
                        result=save(engine,db,key,device,'reminder',category,token,payload,now)
                        if result: emitted.append(result)
                        current['triggeredAt']=payload['triggeredAt']
                    sessions[key_id]={**current,'first':first,'last':end,'intervals':intervals,'token':token}
            db.execute('INSERT OR REPLACE INTO assistant_activity_baselines VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                       (*ident,int(enabled),version,0,blocked,observed or 0,0,json.dumps(state)))
        old=db.execute('SELECT event_key FROM assistant_activity_events WHERE tenant=? AND project=? AND sn=? AND created<?',(*scope,cutoff)).fetchall()
        for row in old: db.execute('DELETE FROM assistant_activity_changes WHERE event_key=?',(row['event_key'],))
        db.execute('DELETE FROM assistant_activity_events WHERE tenant=? AND project=? AND sn=? AND created<?',(*scope,cutoff))
    return emitted
