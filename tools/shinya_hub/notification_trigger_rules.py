"""Stateful reminders from proven network activity, never screen-use guesses."""
import json
import math
from datetime import datetime, timedelta

from .daily import CHINA
from .notification_preferences import configuration, selected


def night(intervals):
    for start, end in intervals:
        day = datetime.fromtimestamp(start, CHINA).replace(hour=0, minute=0, second=0, microsecond=0)
        while day.timestamp() < end:
            for low, high in ((day.timestamp(), (day + timedelta(hours=6)).timestamp()),
                              ((day + timedelta(hours=22)).timestamp(), (day + timedelta(days=1)).timestamp())):
                if max(low, start) < min(high, end):
                    return True
            day += timedelta(days=1)
    return False


def trigger(engine, db, key, device, name, rule, report, ranges, observed, online, now, project_id):
    from .notification_activity import identity, save, union
    config = configuration(rule.get('settings'))
    versions = rule.get('categoryUpdatedAt') or {}
    scope = (key.tenant, key.project, key.sn)
    healthy = (observed is not None and 0 <= now-observed <= engine.max_age
               and report.get('stale') is not True and report.get('basis') == 'minutes'
               and type(online) is bool)
    emitted = []
    for category in ('usage', 'game', 'video', 'payment', 'social'):
        field = 'enable' if category == 'usage' else category
        settings = config[field]
        enabled = rule.get(field) is True
        ident = (*scope, device, category)
        previous = db.execute('SELECT * FROM assistant_activity_baselines WHERE tenant=? AND project=? AND sn=? AND device=? AND category=?', ident).fetchone()
        version = versions.get(field) or rule.get('updatedAt') or (previous['rule_version'] if previous else now)
        old = json.loads(previous['state']) if previous else {}
        reset = (previous is None or previous['rule_version'] != version or not previous['enabled']
                 or old.get('triggerVersion') != 3)
        state = old if not reset else {'schemaVersion': 2, 'triggerVersion': 3, 'sessions': {}}
        blocked = max(previous['blocked_before'] if previous else now, now if reset or not enabled or not healthy else 0)
        if not enabled or not healthy:
            state = {'schemaVersion': 2, 'triggerVersion': 3, 'sessions': {}}
        if healthy and previous and observed < previous['observed']:
            continue  # Late responses cannot move an observation/baseline backwards.

        def emit(kind, episode, app=None, access=None):
            app = app or {}
            token = identity(*scope, device, 'reminder', category, kind, app.get('appId', ''), episode['first'])
            sent = episode.setdefault('sent', [])
            if kind in sent:
                return
            labels = {'start': '设备开始用网提醒', 'end': '设备结束用网提醒', 'night': '设备深夜用网提醒',
                      'long': '设备长时间用网提醒', 'duration': {'game': '游戏提醒', 'video': '视频提醒', 'social': '社交提醒'}.get(category),
                      'access': '支付类访问提醒'}
            content = ('检测到设备连续网络活动' if kind == 'start' else
                       '连续10分钟未检测到有效网络活动' if kind == 'end' else
                       '检测到22:00—06:00期间的网络活动' if kind == 'night' else
                       '连续网络活动累计达到'+str(settings['longMinutes'])+'分钟' if kind == 'long' else
                       '检测到'+app.get('appName', '')+'服务访问，不代表已完成付款' if kind == 'access' else
                       app.get('appName', '')+'网络活动累计达到'+str(settings.get('minutes', 0))+'分钟')
            payload = {'projectId': project_id or key.project, 'deviceName': name,
                       'title': labels[kind], 'content': content, 'ruleKind': kind,
                       'appId': app.get('appId', ''), 'appName': app.get('appName', ''),
                       'startTime': int(episode['first']*1000), 'lastActivityTime': int(episode['last']*1000),
                       'triggeredAt': int((access if access is not None else episode['last']+600 if kind=='end' else episode['last'])*1000), 'accessTime': int(access*1000) if access is not None else None,
                       'source': 'children.report.sessionRanges', 'measurementBasis': 'observed-network-activity'}
            value = save(engine, db, key, device, 'reminder', category, token, payload, now)
            if value:
                emitted.append(value)
            sent.append(kind)

        if enabled and healthy and not reset:
            sessions = state.setdefault('sessions', {})
            if not previous or observed-previous['observed'] > engine.max_age:
                # An outage is not ten minutes of observed inactivity.
                state['healthySince'] = observed
            else:
                state.setdefault('healthySince', previous['observed'])
            candidates = [r for r in ranges if r['end'] > blocked and
                          (category == 'usage' and r['located'] or r['category'] == category)]
            if category == 'payment':
                for sample in candidates:
                    if sample['start'] < blocked or not selected(config, field, sample['appId']):
                        continue
                    event_key = identity(sample['appId'], sample['sourceEventId'] or sample['start'])
                    episode = sessions.setdefault(event_key, {'first': sample['start'], 'last': sample['end']})
                    emit('access', episode, sample, sample['start'])
            else:
                groups = {}
                for sample in candidates:
                    if not sample['located'] or category != 'usage' and not selected(config, field, sample['appId']):
                        continue
                    groups.setdefault('' if category == 'usage' else sample['appId'], []).append(sample)
                for app_id, samples in groups.items():
                    app = {} if category == 'usage' else samples[0]
                    points = union([[max(s['start'], math.ceil(blocked/60)*60), min(s['end'], math.floor(observed/60)*60)] for s in samples])
                    current = sessions.get(app_id)
                    for begin, end in points:
                        if current and end <= current['last']:
                            continue
                        if current and begin-current['last'] >= 600:
                            if category == 'usage' and settings['end'] and observed-state['healthySince'] >= 600:
                                emit('end', current)
                            current = None
                        if current is None:
                            current = {'first': begin, 'last': begin, 'intervals': [], 'sent': []}
                        current['intervals'] = union(current['intervals']+[[max(begin,current['last']), end]])
                        current['last'] = end
                        minutes = sum(b-a for a,b in current['intervals'])/60
                        if category == 'usage':
                            if settings['start'] and minutes >= 5: emit('start', current)
                            if settings['long'] and minutes >= settings['longMinutes']: emit('long', current)
                            if settings['night'] and night(current['intervals']): emit('night', current)
                        elif settings['durationEnabled'] and minutes >= settings['minutes']:
                            emit('duration', current, app)
                    if current:
                        sessions[app_id] = current
                current = sessions.get('') if category == 'usage' else None
                if (current and settings['end'] and observed-current['last'] >= 600
                        and observed-state['healthySince'] >= 600):
                    emit('end', current)
            cutoff = (datetime.fromtimestamp(now, CHINA).replace(hour=0,minute=0,second=0,microsecond=0)-timedelta(days=2)).timestamp()
            state['sessions'] = {k:v for k,v in sessions.items() if v.get('last',0) >= cutoff}
        db.execute('INSERT OR REPLACE INTO assistant_activity_baselines VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                   (*ident, int(enabled), version, 0, blocked, observed or 0, 0, json.dumps(state)))
    return emitted
