import requests,re,json
from urllib.parse import urljoin,urlsplit
from pathlib import Path
c=requests.Session();c.trust_env=False
r=c.get('https://noc.ruijie.com.cn/webproxy/sso/back',params={'rewrite':'off','back':'https://noc.ruijie.com.cn/macc5/admin/'},timeout=(4,15))
print(json.dumps({'status':r.status_code,'host':urlsplit(r.url).hostname,'path':urlsplit(r.url).path,'fields':re.findall(r'<input[^>]*name=[\"\x27]([^\"\x27]+)',r.text),'scripts':re.findall(r'<script[^>]*src=[\"\x27]([^\"\x27]+)',r.text)[:15]}))
p=Path('test/_analysis/oct10-priority/macc-public')
for n,src in enumerate(re.findall(r'<script[^>]*src=[\"\x27]([^\"\x27]+)',r.text)):
 url=urljoin(r.url,src)
 if urlsplit(url).hostname not in ('noc.ruijie.com.cn','sso.ruijie.com.cn','account.ruijie.com.cn'):continue
 a=c.get(url,timeout=(4,15));(p/('sso-script-'+str(n)+'.js')).write_text(a.text,encoding='utf8');print(json.dumps({'script':n,'host':urlsplit(url).hostname,'path':urlsplit(url).path,'bytes':len(a.content)}))
