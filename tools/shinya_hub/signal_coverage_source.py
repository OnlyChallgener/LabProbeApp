"""Daily wireless weak episodes, from continuous native observation pairs."""
import time
from datetime import datetime
from .daily import CHINA,day_of,pieces,MAX_GAP
from .identifiers import mac_key

# Preserve the local 2.4G weak boundary, and use the inspected APK's separate
# 5G/6G middle-level boundary. Vendor cloud historical smoothing is private;
# these limits define local observed episodes, not a claim of an identical score.
LIMITS={'2.4G':-75,'5G':-84,'6G':-84}
MAX_EPISODE_GAP = 180

def initialize(db):
    db.executescript('''
        CREATE TABLE IF NOT EXISTS signal_weak_episode (
            tenant TEXT,project TEXT,day TEXT,mac TEXT,sn TEXT,start REAL,end REAL,
            PRIMARY KEY(tenant,project,day,mac,start));
        CREATE TABLE IF NOT EXISTS signal_seen_day (
            tenant TEXT,project TEXT,day TEXT,mac TEXT,sn TEXT,observed REAL,
            weak INTEGER,PRIMARY KEY(tenant,project,day,mac));
    ''')

def weak(row):
    band=row.get('band')
    try:value=float(row.get('rssi'))
    except (ValueError,TypeError):return None
    if band not in LIMITS or not -120<=value<0:return None
    return value<=LIMITS[band]

def record(db,tenant,project,sn,mac,before,row,start,end,continuous):
    current=weak(row)
    if current is not None:
        db.execute('''INSERT INTO signal_seen_day VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(tenant,project,day,mac) DO UPDATE SET sn=excluded.sn,
            observed=excluded.observed,weak=excluded.weak WHERE excluded.observed>observed''',
            (tenant,project,day_of(end),mac,sn,end,int(current)))
    is_weak_now = current is True
    is_weak_before = weak(before) is True
    if not is_weak_now and not is_weak_before:
        return
    if not continuous and not is_weak_now:
        return
    for day,a,b in pieces(start,end):
        last=db.execute('''SELECT * FROM signal_weak_episode WHERE tenant=? AND project=?
            AND day=? AND mac=? ORDER BY start DESC LIMIT 1''',(tenant,project,day,mac)).fetchone()
        if last and last['sn']==sn and (a - last['end']) <= MAX_EPISODE_GAP and a >= last['start']:
            db.execute('''UPDATE signal_weak_episode SET end=MAX(end, ?) WHERE tenant=? AND project=?
                AND day=? AND mac=? AND start=?''',(b,tenant,project,day,mac,last['start']))
        else:
            db.execute('INSERT OR IGNORE INTO signal_weak_episode VALUES(?,?,?,?,?,?,?)',
                       (tenant,project,day,mac,sn,a,b))
    # Keep the same retention horizon as local telemetry, never an unbounded log.
    cutoff=day_of(end-14*86400)
    db.execute('DELETE FROM signal_weak_episode WHERE tenant=? AND project=? AND day<?',(tenant,project,cutoff))
    db.execute('DELETE FROM signal_seen_day WHERE tenant=? AND project=? AND day<?',(tenant,project,cutoff))

def summary(store,tenant,project,now=None):
    now=time.time() if now is None else now
    day=day_of(now)
    with store.connect() as db:
        seen={r['mac']:dict(r) for r in db.execute('SELECT * FROM signal_seen_day WHERE tenant=? AND project=? AND day=?',
              (tenant,project,day))}
        episodes=[dict(r) for r in db.execute('''SELECT * FROM signal_weak_episode WHERE tenant=? AND project=?
            AND day=? ORDER BY start ASC''',(tenant,project,day))]

    mac_episodes={}
    for row in episodes:
        mac_episodes.setdefault(row['mac'], []).append(row)

    result={}
    for mac, eps in mac_episodes.items():
        merged=[]
        for ep in eps:
            s, e = ep['start'], ep['end']
            if not merged:
                merged.append([s, e])
            else:
                prev_s, prev_e = merged[-1]
                if s - prev_e <= MAX_EPISODE_GAP:
                    merged[-1][1] = max(prev_e, e)
                else:
                    merged.append([s, e])

        total_seconds = sum(e - s for s, e in merged)
        latest = merged[-1] if merged else None
        result[mac] = {
            'seconds': total_seconds,
            'latest_start': latest[0] if latest else None,
            'latest_end': latest[1] if latest else None,
        }

    for mac,row in seen.items():
        value=result.setdefault(mac,{'seconds':0,'latest_start':None,'latest_end':None})
        is_bad=value['seconds']>0 or bool(row['weak'])
        total_seconds=value['seconds']

        if is_bad:
            minutes = max(1, round(total_seconds / 60))
        else:
            minutes = 0

        first_ts = value['latest_start'] if value['latest_start'] is not None else row['observed']
        last_ts = value['latest_end'] if value['latest_end'] is not None else row['observed']

        t1_str = datetime.fromtimestamp(first_ts, CHINA).strftime('%H:%M')
        t2_str = datetime.fromtimestamp(last_ts, CHINA).strftime('%H:%M')
        if t1_str == t2_str and is_bad:
            t2_str = datetime.fromtimestamp(first_ts + 60, CHINA).strftime('%H:%M')

        value.update(
            isBadSignal=is_bad,
            signalObservedToday=True,
            badSignalDuration=f'{minutes//60}小时{minutes%60}分' if is_bad else None,
            lastBadSignalTime=f'{t1_str}-{t2_str}' if is_bad else None,
            badSignalSeconds=round(total_seconds),
            signalHistorySource='firmware-observations'
        )
    return result
