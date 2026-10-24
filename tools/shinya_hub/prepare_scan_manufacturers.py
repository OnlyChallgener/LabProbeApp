"""Package IEEE registration prefixes for offline BSSID manufacturer lookup."""
import csv,gzip,hashlib,io,json,requests,re
from pathlib import Path
OUT=Path('test/_analysis/oct13-official-game/scan-manufacturers');OUT.mkdir(exist_ok=True)
prefixes={};sources=[];s=requests.Session();s.trust_env=False
try:
 for folder,file in [('oui','oui.csv'),('oui28','mam.csv'),('oui36','oui36.csv')]:
  url='https://standards-oui.ieee.org/'+folder+'/'+file;p=OUT/file
  if not p.exists():
   v=s.get(url,timeout=30)
   if v.status_code==418:
    # Official Wireshark export carries the same IEEE registration data.
    url='https://www.wireshark.org/download/automated/data/manuf';p=OUT/'manuf'
    if not p.exists():
     v=s.get(url,timeout=30);v.raise_for_status();p.write_bytes(v.content)
    data=p.read_bytes()
    for line in data.decode('utf8').splitlines():
     if not line or line.startswith('#'):continue
     fields=line.split('\t');address=fields[0].strip();bits=address.split('/')[-1] if '/' in address else '24'
     if bits not in ('24','28','36'):continue
     prefix=address.split('/')[0].replace(':','')[:int(bits)//4].upper()
     name=fields[-1].strip() if len(fields)>2 else fields[1].strip()
     if len(prefix) in (6,7,9) and name:prefixes[prefix]=name
    sources.append({'url':url,'sha256':hashlib.sha256(data).hexdigest(),'rows':len(prefixes),'upstream':'IEEE registrations through official Wireshark export'})
    break
   v.raise_for_status();p.write_bytes(v.content)
  data=p.read_bytes();rows=list(csv.DictReader(io.StringIO(data.decode('utf8-sig'))))
  for row in rows:
   prefix=row['Assignment'].upper();name=row['Organization Name'].strip()
   if len(prefix) in (6,7,9) and name:prefixes[prefix]=name
  sources.append({'url':url,'sha256':hashlib.sha256(data).hexdigest(),'rows':len(rows)})
finally:s.close()
target=Path('D:/Github/ShinyaHub/shinya_hub/resources/bssid-manufacturers.json.gz')
target.write_bytes(gzip.compress(json.dumps(prefixes,ensure_ascii=False,separators=(',',':')).encode(),mtime=0))
(OUT/'provenance.json').write_text(json.dumps(sources,indent=2),encoding='utf8')
print('Offline manufacturer prefixes:',len(prefixes),'bytes:',target.stat().st_size)
