"""Acquire pinned, digest-verified portable engines for this firmware audit."""
import hashlib,json,requests,time,zipfile
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed

PROXY={'http':'http://127.0.0.1:7892','https':'http://127.0.0.1:7892'}
OUT=Path('test/_tools/rea-native');OUT.mkdir(parents=True,exist_ok=True)
def request(url,**kw):
 response=requests.get(url,proxies=PROXY,timeout=(10,45),**kw);response.raise_for_status();return response

def acquire(name,url,size,digest):
 archive=OUT/name
 if not archive.exists() or archive.stat().st_size!=size or hashlib.sha256(archive.read_bytes()).hexdigest()!=digest:
  parts=OUT/(name+'.parts');parts.mkdir(exist_ok=True);step=4*1048576
  def part(offset):
   end=min(size,offset+step)-1;p=parts/str(offset)
   # Every retained part is checked by length and the complete release digest.
   if p.exists() and p.stat().st_size==end-offset+1:return p.stat().st_size
   error=None
   for attempt in range(3):
    try:
     # An expired cached redirect must not stall the remaining ranges.
     fresh=url+('?rea_range='+str(offset)+'-'+str(attempt)+'-'+str(int(time.time())))
     r=request(fresh,headers={'Range':f'bytes={offset}-{end}'})
     assert r.status_code==206 and r.headers['content-range']==f'bytes {offset}-{end}/{size}'
     assert len(r.content)==end-offset+1;p.write_bytes(r.content);return len(r.content)
    except (requests.RequestException,AssertionError) as caught:error=caught
   raise error
  total=0;note=50_000_000
  with ThreadPoolExecutor(max_workers=8) as pool:
   for f in as_completed([pool.submit(part,i) for i in range(0,size,step)]):
    total+=f.result()
    if total>=note:print(name,'MB',round(total/1e6),flush=True);note+=50_000_000
  with archive.open('wb') as target:
   for offset in range(0,size,step):target.write((parts/str(offset)).read_bytes())
  assert archive.stat().st_size==size and hashlib.sha256(archive.read_bytes()).hexdigest()==digest
  print(name,'release digest verified',flush=True)
 dest=OUT/(name+'.unpacked')
 if not (dest/'verified.json').exists():
  dest.mkdir(exist_ok=True);root=dest.resolve()
  with zipfile.ZipFile(archive) as source:
   for member in source.namelist():assert (root/member).resolve().is_relative_to(root),'Archive path escapes output'
   source.extractall(dest)
  (dest/'verified.json').write_text(json.dumps({'name':name,'sha256':digest}),encoding='utf-8')
 return dest

release=request('https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/Ghidra_12.1.4_build').json()
g=next(a for a in release['assets'] if a['name']=='ghidra_12.1.4_PUBLIC_20260921.zip')
j=request('https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&heap_size=normal&vendor=eclipse').json()[0]['binary']['package']
meta={'ghidra':{k:g[k] for k in ('name','size','digest','browser_download_url')},'jdk':j}
(OUT/'releases.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
print('Portable download MB',round((g['size']+j['size'])/1e6),flush=True)
gd=acquire(g['name'],g['browser_download_url'],g['size'],g['digest'].removeprefix('sha256:'))
jd=acquire(j['name'],j['link'],j['size'],j['checksum'])
ghidra=next(gd.glob('ghidra_*'));java=next(jd.glob('jdk-*'))
(OUT/'paths.json').write_text(json.dumps({'GHIDRA_INSTALL_DIR':str(ghidra.resolve()),'JAVA_HOME':str(java.resolve())},indent=2),encoding='utf-8')
print('Portable REA native engine ready',flush=True)
