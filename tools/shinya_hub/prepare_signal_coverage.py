"""Wire daily coverage into existing telemetry and original station fields."""
import os,shutil
from pathlib import Path
from remote_transport import connect_nas,run
ROOT=Path('test/_analysis/oct12-signal-coverage');BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(exist_ok=True)
c=connect_nas()
try:
    for name in ('api.py','projection.py','statistics.py','daily.py','signal_coverage.py'):
        path='/opt/shinya-hub/shinya_hub/'+name
        (BASE/name).write_bytes(run(c,"docker exec shinya-hub sh -c 'if test -f "+path+'; then cat '+path+"; else printf __ABSENT_FILE__; fi'",sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        path='/volume1/docker/shinya-hub/src/shinya_hub/'+name
        (BASE/('source-'+name)).write_bytes(run(c,'if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'))
finally:c.close()
stats=(BASE/'statistics.py').read_text(encoding='utf8')
marker='def initialize(db):\n'
assert stats.count(marker)==1
if 'initialize_coverage' not in stats:
    stats=stats.replace(marker,marker+'    from .signal_coverage import initialize as initialize_coverage\n    initialize_coverage(db)\n')
(OUT/'statistics.py').write_text(stats,encoding='utf8')
daily=(BASE/'daily.py').read_text(encoding='utf8')
marker="            delta = {}\n"
assert daily.count(marker)==1
if 'record_coverage' not in daily:
    daily=daily.replace(marker,"            from .signal_coverage import record as record_coverage\n            record_coverage(db,tenant,project,sn,mac,before,row,\n                old['observed'] if old else observed,observed,continuous)\n"+marker)
(OUT/'daily.py').write_text(daily,encoding='utf8')
projection=(BASE/'projection.py').read_text(encoding='utf8')
marker="    quality = station_metrics(store,tenant,project['projectId'])\n"
assert projection.count(marker)==1
projection=projection.replace(marker,marker+"    from .signal_coverage import summary as coverage_summary\n    coverage=coverage_summary(store,tenant,project['projectId'])\n")
start=projection.index('        if online:\n',projection.index(marker))
end=projection.index('        if sn in acl:',start)
projection=projection[:start]+'''        instant=rssi_value(raw.get('rssi'),row.get('rssi'))
        average=rssi_value(row.get('rssiAvg'),instant)
        row['rssiAvg']=average
        if row.get('wifiBand')=='wire':
            row.update(rssiScore=100,instantRssiScore=100 if online else None,
                       isBadSignal=False,badSignalDuration=None,lastBadSignalTime=None)
        else:
            row['instantRssiScore']=signal_score(instant) if online else None
            row['rssiScore']=signal_score(average)
            history=coverage.get(mac_key(raw['mac']))
            row.update({k:history[k] for k in ('isBadSignal','badSignalDuration','lastBadSignalTime',
                'badSignalSeconds','signalHistorySource')} if history else
                dict(isBadSignal=False,badSignalDuration=None,lastBadSignalTime=None))
            if history:row['isTodayOnline']=True
''' +projection[end:]
(OUT/'projection.py').write_text(projection,encoding='utf8')
api=(BASE/'api.py').read_text(encoding='utf8')
old="online_wireless = [s for s in stations if s.get('online') and s.get('wifiBand') != 'wire']"
new="online_wireless = [s for s in stations if s.get('isTodayOnline') and s.get('wifiBand') != 'wire']"
assert api.count(old)==1
api=api.replace(old,new)
(OUT/'api.py').write_text(api,encoding='utf8')
shutil.copyfile('tools/shinya_hub/signal_coverage_source.py',OUT/'signal_coverage.py')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Daily coverage staged with original field names')
