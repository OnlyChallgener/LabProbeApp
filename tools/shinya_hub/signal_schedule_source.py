"""Validated weekly intervals; Wi-Fi slots are ON times, healthy slots are ECO times."""
import json

DAYS = ('mon','tue','wed','thu','fri','sat','sun')

def merge(rows):
    out=[]
    for a,b in sorted(rows):
        if a>=b: continue
        if out and a<=out[-1][1]: out[-1][1]=max(out[-1][1],b)
        else: out.append([a,b])
    return out

def expand(policies):
    if not isinstance(policies,list) or len(policies)>32: raise ValueError('定时策略格式无效')
    result={d:[] for d in range(1,8)}
    for p in policies:
        if not isinstance(p,dict): raise ValueError('定时策略格式无效')
        days=p.get('weekDays');ranges=p.get('beginEndTimes')
        if not isinstance(days,list) or not days or any(type(d)!=int or not 1<=d<=7 for d in days): raise ValueError('请选择有效星期')
        if not isinstance(ranges,list) or not ranges or len(ranges)>16: raise ValueError('请选择有效时间段')
        for r in ranges:
            a=r.get('beginMinute');b=r.get('endMinute')
            if type(a)!=int or type(b)!=int or not 0<=a<=1439 or not 0<=b<=1440 or a==b: raise ValueError('时间段无效')
            for d in set(days):
                if a<b: result[d].append([a,b])
                else:
                    result[d].append([a,1440]);result[d%7+1].append([0,b])
    return {d:merge(rows) for d,rows in result.items()}

def complement(days):
    out={}
    for d in range(1,8):
        start=0;rows=[]
        for a,b in merge(days.get(d,[])):
            if a>start:rows.append([start,a])
            start=b
        if start<1440:rows.append([start,1440])
        out[d]=rows
    return out

def encode(days):
    def clock(n):return '23:59' if n==1440 else f'{n//60:02d}:{n%60:02d}'
    # Preserve empty days: they mean unavailable, not the absence of a timer.
    return {DAYS[d-1]:[[clock(a),clock(b)] for a,b in rows] for d,rows in days.items()}

def decode(slot):
    if isinstance(slot,str):
        try:slot=json.loads(slot)
        except ValueError:raise ValueError('固件时间组格式无效')
    if not isinstance(slot,dict):raise ValueError('固件时间组格式无效')
    days={d:[] for d in range(1,8)}
    for d,key in enumerate(DAYS,1):
        for r in slot.get(key,[]):
            if not isinstance(r,list) or len(r)!=2:raise ValueError('固件时间段格式无效')
            values=[]
            for v in r:
                h,m=map(int,v.split(':'))
                if not 0<=h<=23 or not 0<=m<=59:raise ValueError('固件时间段格式无效')
                values.append(h*60+m)
            a,b=values
            if b==1439:b=1440
            if a<b:days[d].append([a,b])
            elif a>b:days[d].append([a,1440]);days[d%7+1].append([0,b])
    return {d:merge(rows) for d,rows in days.items()}

def policies(days):
    # Day-local rows avoid ambiguous cross-midnight joins when several days
    # share a boundary. The UI accepts any number of grouped policies.
    grouped={}
    for d in range(1,8):
        rows=merge(days.get(d,[]))
        if not rows:continue
        key=json.dumps(rows)
        grouped.setdefault(key,{'weekDays':[],'beginEndTimes':[{'beginMinute':a,'endMinute':b} for a,b in rows]})['weekDays'].append(d)
    return list(grouped.values())
