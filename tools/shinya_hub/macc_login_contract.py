"""Inspect public authentication entry pages, never capture passwords/cookies."""
import requests,re,json
from pathlib import Path
p=Path('test/_analysis/oct10-priority/macc-public');c=requests.Session();c.trust_env=False
for path in ('/login','/webproxy/common/api?/org/account/info'):
 r=c.get('https://noc.ruijie.com.cn'+path,timeout=(4,12));name='public-login' if path=='/login' else 'anonymous-account'
 (p/(name+'.txt')).write_text(r.text,encoding='utf8')
 print(json.dumps({'path':path,'status':r.status_code,'url':r.url,'bytes':len(r.content),'scripts':re.findall(r'<script[^>]*src=[\"\x27]([^\"\x27]+)',r.text)[:15]}))
