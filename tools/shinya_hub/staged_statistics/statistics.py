"""Local, inspectable statistics from successful native observations only."""
import json
import math
import re
import time
from datetime import datetime

from .daily import CHINA, day_of, summary as traffic_summary
from .telemetry import number, wan_rates
from .identifiers import mac_key


def initialize(db):
    db.executescript('''
        CREATE TABLE IF NOT EXISTS rate_samples (
            tenant TEXT, project TEXT, sn TEXT, slot INTEGER, observed REAL,
            down REAL, up REAL, PRIMARY KEY(tenant,project,sn,slot));
        CREATE TABLE IF NOT EXISTS quality_samples (
            tenant TEXT, project TEXT, sn TEXT, kind TEXT, slot INTEGER,
            observed REAL, value TEXT, PRIMARY KEY(tenant,project,sn,kind,slot));
        CREATE TABLE IF NOT EXISTS station_quality_day (
            tenant TEXT, project TEXT, day TEXT, mac TEXT,
            rx_sum REAL DEFAULT 0, rx_seconds REAL DEFAULT 0,
            tx_sum REAL DEFAULT 0, tx_seconds REAL DEFAULT 0,
            rssi_sum REAL DEFAULT 0, rssi_seconds REAL DEFAULT 0,
            PRIMARY KEY(tenant,project,day,mac));
        CREATE TABLE IF NOT EXISTS station_drop (
            tenant TEXT, project TEXT, mac TEXT, observed REAL,
            PRIMARY KEY(tenant,project,mac,observed));
    ''')


def phy(value):
    match = re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*([KMG]?)(?:bps)?\s*', str(value), re.I)
    if not match:
        return None
    rate = float(match[1]) * {'':1, 'K':.001, 'M':1, 'G':1000}[match[2].upper()]
    return rate if 0 <= rate <= 100000 else None


def rssi(value):
    try:result=float(value)
    except (ValueError, TypeError):return None
    return result if math.isfinite(result) and -120 <= result < 0 else None


def record_station_interval(db, tenant, project, mac, day, before, row, seconds):
    if seconds <= 0 or before.get('band') not in ('2.4G','5G','6G'):return
    rx, tx, signal = phy(before.get('rxrate')), phy(before.get('txrate')), rssi(before.get('rssi'))
    # Left-endpoint values cover only a continuous successful observation pair.
    db.execute('''INSERT INTO station_quality_day VALUES(?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(tenant,project,day,mac) DO UPDATE SET
        rx_sum=rx_sum+excluded.rx_sum, rx_seconds=rx_seconds+excluded.rx_seconds,
        tx_sum=tx_sum+excluded.tx_sum, tx_seconds=tx_seconds+excluded.tx_seconds,
        rssi_sum=rssi_sum+excluded.rssi_sum,rssi_seconds=rssi_seconds+excluded.rssi_seconds''',
        (tenant,project,day,mac,(rx or 0)*seconds,seconds if rx is not None else 0,
         (tx or 0)*seconds,seconds if tx is not None else 0,
         (signal or 0)*seconds,seconds if signal is not None else 0))


def record_drop(db, tenant, project, mac, observed):
    db.execute('INSERT OR IGNORE INTO station_drop VALUES(?,?,?,?)',(tenant,project,mac,observed))


def quality_value(kind, value):
    if not isinstance(value,dict):raise ValueError('Invalid native statistics')
    if kind == 'overview':
        result={}
        for field in ('runtime','temp','memutil'):
            v=number(value.get(field))
            if v is not None:result[field]=v
        cpu=number(str(value.get('cpuutil','')).removesuffix('%'))
        if cpu is not None and cpu <= 100:result['cpu']=cpu
        if value.get('status') in ('connected','disconnected','connecting'):
            result['connected']=value['status']=='connected'
        if not result:raise ValueError('No native overview metrics')
        return result
    result={}
    for radio,band in [('radio0','2.4G'),('radio1','5G')]:
        raw=value.get(radio,{})
        utilization=number(raw.get('chutil'))
        noise=rssi(raw.get('channf'))
        metrics={}
        if utilization is not None and utilization <= 100:metrics['utilization']=utilization
        if noise is not None:metrics['noise']=noise
        if metrics:result[band]=metrics
    if not result:raise ValueError('No native radio metrics')
    return result


def record_resource(store, key, command, value, observed):
    if command.namespace != 'dev_sta':return
    if command.module == 'flow' and command.data == {'func':'interface_info'}:
        rate=wan_rates(value)['wan']
        with store.connect() as db:
            db.execute('''INSERT INTO rate_samples VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(tenant,project,sn,slot) DO UPDATE SET
                observed=excluded.observed,down=excluded.down,up=excluded.up
                WHERE excluded.observed > observed''',
                (key.tenant,key.project,key.sn,int(observed//30),observed,rate['down'],rate['up']))
    elif command.module in ('overview','radioInfo'):
        metrics=quality_value(command.module,value)
        with store.connect() as db:
            db.execute('''INSERT INTO quality_samples VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(tenant,project,sn,kind,slot) DO UPDATE SET
                observed=excluded.observed,value=excluded.value WHERE excluded.observed > observed''',
                (key.tenant,key.project,key.sn,command.module,int(observed//30),observed,json.dumps(metrics)))


def bounds(now):
    start=datetime.fromtimestamp(now,CHINA).replace(hour=0,minute=0,second=0,microsecond=0).timestamp()
    return start,start+86400


def station_metrics(store,tenant,project,now=None):
    now=time.time() if now is None else now
    start,end=bounds(now)
    with store.connect() as db:
        rows=[dict(r) for r in db.execute('SELECT * FROM station_quality_day WHERE tenant=? AND project=? AND day=?',
                                        (tenant,project,day_of(now)))]
        drops={r['mac']:dict(r) for r in db.execute('''SELECT mac,COUNT(*) AS count,MAX(observed) AS last FROM station_drop
            WHERE tenant=? AND project=? AND observed>=? AND observed<? GROUP BY mac''',(tenant,project,start,end))}
    result={}
    for row in rows:
        row['rxRateAvg']=round(row['rx_sum']/row['rx_seconds'],1) if row['rx_seconds'] else None
        row['txRateAvg']=round(row['tx_sum']/row['tx_seconds'],1) if row['tx_seconds'] else None
        row['dropCount']=drops.get(row['mac'],{}).get('count',0)
        row['lastDropAt']=drops.get(row['mac'],{}).get('last')
        result[row['mac']]=row
    return result


def speed(store,tenant,project,sn,now=None, *, logarithmic=False):
    now=time.time() if now is None else now
    start,end=bounds(now)
    with store.connect() as db:
        samples=[dict(r) for r in db.execute('''SELECT * FROM rate_samples
            WHERE tenant=? AND project=? AND sn=? AND observed>=? AND observed<? ORDER BY observed''',
            (tenant,project,sn,start,end))]
        recent=[dict(r) for r in db.execute('''SELECT * FROM rate_samples
            WHERE tenant=? AND project=? AND sn=? AND observed>=? AND observed<=? ORDER BY observed''',
            (tenant,project,sn,now-3600,now))]
    # Weight only observed intervals; outages do not count as zero traffic.
    # This window also crosses midnight independently of today's curve.
    seconds=0;recent_down=0;recent_up=0
    for index,row in enumerate(recent):
        until=recent[index+1]['observed'] if index+1<len(recent) else now
        duration=max(0,min(until,now,row['observed']+90)-row['observed'])
        seconds+=duration
        recent_down+=row['down']*duration;recent_up+=row['up']*duration
    # Five-minute observed averages. Missing buckets remain absent (never zeros).
    buckets={}
    for sample in samples:
        index=int((sample['observed']-start)//300)
        buckets.setdefault(index,[]).append(sample)
    slots=sorted(buckets)
    download=[round(sum(r['down'] for r in buckets[i])/len(buckets[i])/1024,2) for i in slots]
    upload=[round(sum(r['up'] for r in buckets[i])/len(buckets[i])/1024,2) for i in slots]
    length=slots[-1]+1 if slots else 0
    rx=[None]*length;tx=[None]*length
    for i,down,up in zip(slots,download,upload):rx[i]=down;tx[i]=up
    maximum=max((value/1024 for value in download+upload),default=0)
    axis_scale=10**math.ceil(math.log10(max(maximum,0.001)/9999))
    axis_unit='Mbps' if maximum>=1 else 'Kbps'
    axis_factor=1 if axis_unit=='Mbps' else 1024
    def tick(value):
        if value==0:return '0'
        digits=0 if value>=1000 else 1 if value>=100 else 2 if value>=1 else 3
        result=f'{value:.{digits}f}'
        return result.rstrip('0').rstrip('.') if '.' in result else result
    ticks=[tick(axis_scale*(10**level-1)*axis_factor) for level in range(5)]
    groups=[]
    for i in slots:
        if not groups or i != groups[-1][-1]+1:groups.append([])
        groups[-1].append(i)
    datasets=[]
    for field,label,color in [(rx,'下载速率',0xFF6796FC),(tx,'上传速率',0xFF14C9C9)]:
        # Match the official speed view: a lightly smoothed line, with a
        # restrained download area. Keep uploads as a line so both fills do
        # not merge into a solid band at the baseline. Gaps remain separate
        # datasets above, so smoothing never fabricates traffic across them.
        is_download = field is rx
        for group in groups:
            datasets.append({'values':[{'x':i,'y':(math.log10(1+field[i]/1024/axis_scale)
                                                   if logarithmic else field[i]/1024)} for i in group],
                'label':label,'config':{'color':color-2**32,'fillColor':color-2**32,
                    'drawValues':False,'drawFilled':is_download,'fillAlpha':38,
                    'drawCircles':len(group)==1,'circleRadius':2.2,
                    'mode':'CUBIC_BEZIER','drawCubicIntensity':0.10,'lineWidth':1.8}})
    stations=station_metrics(store,tenant,project,now)
    rx_seconds=sum(r['rx_seconds'] for r in stations.values());tx_seconds=sum(r['tx_seconds'] for r in stations.values())
    traffic=traffic_summary(store,tenant,project,now)
    return {'rxRateList':rx,'txRateList':tx,'data':{'dataSets':datasets},
            **({'yTicks':ticks,'yAxisUnit':axis_unit} if logarithmic else {}),
            'lastHourRx':round(recent_down/seconds/1_000_000,2) if seconds else None,
            'lastHourTx':round(recent_up/seconds/1_000_000,2) if seconds else None,
            'timeList':[datetime.fromtimestamp(start+i*300,CHINA).strftime('%H:%M') for i in range(length)],
            'bucketIndices':slots,'statisticsCoverageStartAt':samples[0]['observed'] if samples else None,
            'statisticsObservedAt':samples[-1]['observed'] if samples else None,
            'allDaySpeed':round(sum(r['rx_sum'] for r in stations.values())/rx_seconds,1) if rx_seconds else None,
            'allDaySpeedTx':round(sum(r['tx_sum'] for r in stations.values())/tx_seconds,1) if tx_seconds else None,
            'totalDownloadBytes':sum(r['down'] for r in traffic['rows'].values()),
            'totalUploadBytes':sum(r['up'] for r in traffic['rows'].values()),
            'source':'hub-native-observation','bucketSeconds':300,'missingBuckets':'not-observed'}


def clamp(value):return max(0,min(100,value))


def stability(store,tenant,project,sn,now=None):
    now=time.time() if now is None else now
    start,end=bounds(now)
    with store.connect() as db:
        observations=[dict(r) for r in db.execute('''SELECT * FROM quality_samples
            WHERE tenant=? AND project=? AND sn=? AND observed>=? AND observed<? ORDER BY observed''',
            (tenant,project,sn,start,end))]
    overviews=[{**r,'value':json.loads(r['value'])} for r in observations if r['kind']=='overview']
    radios=[{**r,'value':json.loads(r['value'])} for r in observations if r['kind']=='radioInfo']
    stations=station_metrics(store,tenant,project,now);traffic=traffic_summary(store,tenant,project,now)
    metrics=[]
    def metric(tag,label,value,weight,basis):
        if value is not None:metrics.append({'tag':tag,'name':label,'value':round(clamp(value),1),'weight':weight,'basis':basis})
    signal_seconds=sum(r['rssi_seconds'] for r in stations.values())
    signal=sum(r['rssi_sum'] for r in stations.values())/signal_seconds if signal_seconds else None
    metric('coverScore','信号质量',clamp((signal+90)*2.5) if signal is not None else None,25,
           {'meanRssiDbm':round(signal,1) if signal is not None else None,'stationSeconds':signal_seconds})
    # SNR is sampled against the same radio's latest noise, not invented QoE.
    latest_noise=radios[-1]['value'] if radios else {}
    with store.connect() as db:
        live=[json.loads(r['value']) for r in db.execute('SELECT value FROM traffic_baseline WHERE tenant=? AND project=? AND sn=?',
                                                      (tenant,project,sn))]
    snrs=[]
    for row in live:
        signal_now=rssi(row.get('rssi'));noise=latest_noise.get(row.get('band'),{}).get('noise')
        if signal_now is not None and noise is not None:snrs.append(signal_now-noise)
    metric('qoeScore','信噪质量',sum(clamp(s/30*100) for s in snrs)/len(snrs) if snrs else None,10,{'snrDb':snrs})
    online_hours=sum(r['online'] for mac,r in traffic['rows'].items() if mac in stations)/3600
    drops=sum(r['dropCount'] for r in stations.values())
    metric('smartScore','无线连续性',100-20*drops/online_hours if online_hours >= 1/12 else None,20,
           {'confirmedDrops':drops,'observedStationHours':round(online_hours,3)})
    reboots=0;runtime_intervals=0
    for a,b in zip(overviews,overviews[1:]):
        if 0<b['observed']-a['observed']<=120 and all('runtime' in r['value'] for r in (a,b)):
            runtime_intervals+=1;reboots+=b['value']['runtime']<a['value']['runtime']
    metric('apStableScore','路由运行稳定',100-20*reboots if runtime_intervals else None,15,{'observedReboots':reboots,'intervals':runtime_intervals})
    for band,tag in [('2.4G','netSaturationScore2g'),('5G','netSaturationScore5g')]:
        values=[r['value'][band]['utilization'] for r in radios if 'utilization' in r['value'].get(band,{})]
        metric(tag,band+'信道空闲',100-sum(values)/len(values) if values else None,10,{'utilizationSamples':len(values)})
    cpus=[r['value']['cpu'] for r in overviews if 'cpu' in r['value']]
    metric('netRelyScore','CPU余量',100-sum(cpus)/len(cpus) if cpus else None,5,{'cpuSamples':len(cpus)})
    connected_seconds=known_seconds=0
    for a,b in zip(overviews,overviews[1:]):
        interval=b['observed']-a['observed']
        if 0<interval<=120 and 'connected' in a['value'] and 'connected' in b['value']:
            known_seconds+=interval
            if a['value']['connected'] and b['value']['connected']:connected_seconds+=interval
    metric('linkStableScore','WAN连接在线',100*connected_seconds/known_seconds if known_seconds else None,5,
           {'connectedSeconds':round(connected_seconds),'observedSeconds':round(known_seconds)})
    score=round(sum(m['value']*m['weight'] for m in metrics)/sum(m['weight'] for m in metrics),1) if metrics else None
    cur={m['tag']:m['value'] for m in metrics}
    # String zero is truthy in the original App's score rendering guard.
    if score is not None:cur['overallScore']=str(score)
    color=0xFF25BCBF-2**32
    chart={'data':{'dataSets':[{'values':[m['value'] for m in metrics],
        'label':'您的家庭水平','config':{'color':color,'fillColor':color,'drawValues':False,'drawFilled':True,'lineWidth':1}}] if metrics else []},
        'xAxis':{'valueFormatter':[m['name'] for m in metrics],'textSize':10,'textColor':0xFF8C8C8C-2**32}}
    return {'curRadarScore':cur,'data':chart,'metrics':metrics,'scoreMethod':'hub-observed-v1',
            'statisticsCoverageStartAt':min((r['observed'] for r in observations),default=None),
            'statisticsObservedAt':max((r['observed'] for r in observations),default=None),
            'scope':'observed-period-only','unknownDimensions':'omitted','externalInternetProbe':False}
