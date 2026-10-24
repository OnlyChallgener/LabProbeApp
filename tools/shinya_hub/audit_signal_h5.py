"""Read official H5 contracts using the user's existing encrypted account session."""
import hashlib,json,os,sys,time,re
from pathlib import Path
from urllib.parse import urlsplit
import requests
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.vault import ConfigVault

OUT=Path('test/_analysis/oct12-signal-tools/official-h5');OUT.mkdir(parents=True,exist_ok=True)
session=requests.Session();session.trust_env=False
vault=ConfigVault(Path('test/_keys/shinya-official-account'))
data=vault.load()
assert data['accountHash']==hashlib.sha256(os.environ['SHINYA_OFFICIAL_ACCOUNT'].encode()).hexdigest()
headers={'Authorization':'Bearer '+data['token'],'type':'shinyaApp'}
def clean(v):
    if isinstance(v,dict):return {k:('[redacted]' if any(w in k.lower() for w in ('token','secret','password','signature','credential','accesskey')) else clean(x)) for k,x in v.items()}
    if isinstance(v,list):return [clean(x) for x in v]
    if isinstance(v,str) and v.startswith(('http:','https:')):
        u=urlsplit(v);return u.scheme+'://'+u.netloc+u.path+('?[redacted]' if u.query else '')
    return v
evidence=[]
for pid,sn in [('18850348','G1TD8RX039025'),('17953001','G1TQ88T702111')]:
    for path,method,payload in [('/api/v1/device/state/getLastScan','POST',{'projectId':pid,'sn':sn}),
        ('/api/v1/netquality/signal/simulation/getSignalSimulationResult','GET',{'projectId':pid})]:
        r=session.request(method,'https://shinyaapp.ruijie.com.cn'+path,headers={**headers,'projectId':pid},
            json=payload if method=='POST' else None,params=payload if method=='GET' else None,timeout=30)
        v=r.json();safe=clean(v)
        name=('scan' if 'getLastScan' in path else 'floor')+'-'+pid+'.json'
        (OUT/name).write_text(json.dumps(safe,ensure_ascii=False,indent=2),encoding='utf8')
        evidence.append({'project':pid,'path':path,'http':r.status_code,'code':v.get('code'),
            'dataFields':sorted(v['data']) if isinstance(v.get('data'),dict) else type(v.get('data')).__name__})
runtime=Path('test/_analysis/oct10-accounts/official-h5/script0.js').read_text(encoding='utf8')
for chunk in [828,680,515,63]:
    matches=re.findall(r'(?:^|[,\{])'+str(chunk)+r':"([0-9a-f]+)"',runtime)
    for h in matches:
        for ext in ['js','css']:
            url=f'https://sy-oss-public.ruijie.com.cn/app-h5/static/{ext}/{chunk}.{h}.{ext}'
            r=session.get(url,timeout=30)
            if r.status_code==200 and not r.content.lstrip().startswith(b'<html'):
                (OUT/f'{chunk}.{h}.{ext}').write_bytes(r.content)
                evidence.append({'publicAsset':url,'bytes':len(r.content)})
(OUT/'audit.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(evidence,ensure_ascii=False))
