"""Change only APP-facing rounded rates; preserve stored sample precision."""
from pathlib import Path
p=Path('D:/Github/ShinyaHub/shinya_hub/statistics.py');s=p.read_text(encoding='utf8')
if 'def display_rate' not in s:
 s=s.replace('def rssi(value):', '''def display_rate(value):
    # Nonnegative Mbps, conventional half-up rounding rather than banker's rounding.
    return int(math.floor(value + .5)) if value is not None else None


def rssi(value):''')
 for field in ('rx','tx'):
  s=s.replace(f"round(row['{field}_sum']/row['{field}_seconds'],1)",f"display_rate(row['{field}_sum']/row['{field}_seconds'])")
  s=s.replace(f"round(sum(r['{field}_sum'] for r in stations.values())/{field}_seconds,1)",f"display_rate(sum(r['{field}_sum'] for r in stations.values())/{field}_seconds)")
 s=s.replace('round(recent_down/seconds/1_000_000,2)','display_rate(recent_down/seconds/1_000_000)').replace('round(recent_up/seconds/1_000_000,2)','display_rate(recent_up/seconds/1_000_000)')
 p.write_text(s,encoding='utf8')
print('Only display values round to integers; accumulated rates remain precise')
