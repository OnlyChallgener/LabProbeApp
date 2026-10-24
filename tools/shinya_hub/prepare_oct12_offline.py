"""Guard offline time projections; preserve an actual firmware disconnect time."""
import os,shlex
from pathlib import Path
from remote_transport import connect_nas,run
ROOT=Path('test/_analysis/oct12-offline');BASE=ROOT/'baseline';OUT=ROOT/'deployment';BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(exist_ok=True)
c=connect_nas()
try:
 for path,target,inside in [('/opt/shinya-hub/shinya_hub/projection.py',BASE/'projection.py',True),('/volume1/docker/shinya-hub/src/shinya_hub/projection.py',BASE/'source-projection.py',False)]:
  target.write_bytes(run(c,'docker exec shinya-hub cat '+path if inside else 'cat '+path,sudo_password=os.environ['SHINYA_NAS_PASSWORD'] if inside else None))
finally:c.close()
s=(BASE/'projection.py').read_text(encoding='utf8');s=s.replace('import hashlib','import hashlib\nimport time',1)
anchor='def rssi_value(*candidates):';assert anchor in s
s=s.replace(anchor,'''def offline_time(value, now=None):
    from .daily import CHINA
    now=time.time() if now is None else now
    if value is None or isinstance(value,bool):return None
    try:
        if isinstance(value,(int,float)) or str(value).isdigit():
            stamp=float(value)
            if stamp>100000000000:stamp/=1000
        else:
            text=str(value).strip()
            parsed=datetime.fromisoformat(text.replace('Z','+00:00'))
            if parsed.tzinfo is None:parsed=parsed.replace(tzinfo=CHINA)
            stamp=parsed.timestamp()
        if not 946684800<=stamp<=now+30:return None
        return datetime.fromtimestamp(stamp,CHINA).strftime('%Y-%m-%d %H:%M:%S')
    except (ValueError,TypeError,OverflowError,OSError):return None


'''+anchor)
old="""        if stats.get('lastDropAt'):
            from .daily import CHINA
            row['latestDevTime']=datetime.fromtimestamp(stats['lastDropAt'],CHINA).strftime('%Y-%m-%d %H:%M:%S')
        else:row['latestDevTime']=None"""
assert old in s
s=s.replace(old,"""        row['latestDevTime']=offline_time(stats.get('lastDropAt')) or offline_time(row.get('inactivetime'))
        row['offlineDurationKnown']=row['latestDevTime'] is not None""")
(OUT/'projection.py').write_text(s,encoding='utf8');compile(s,'projection.py','exec');print('Prepared valid offline-time fallback')
