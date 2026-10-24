import re,json
from pathlib import Path
import requests
p=Path('test/_analysis/oct10-priority/macc-public')
s=(p/'vue2Compat-DwKolcHI.js').read_text(encoding='utf8')
for needle in ('function ue(', 'baseURL:', 'base.cxt', 'LOGIN,', 'pe.LOGIN', 'vaildTime:', 'tunnelSuccess(e)', 'remotePort:e.remotePort', 'authToken'):
 print('\n',needle)
 for m in list(re.finditer(re.escape(needle),s))[:4]:print(s[max(0,m.start()-200):m.end()+650])
c=requests.Session();c.trust_env=False
for url,name in [('https://noc.ruijie.com.cn/macc5/assets/index-DGZNE4ny.js','login-js'),('https://noc.ruijie.com.cn/macc5/admin/','admin-root')]:
 r=c.get(url,timeout=(4,15));(p/(name+'.txt')).write_text(r.text,encoding='utf8');print(name,r.status_code,len(r.content),r.url)
s=(p/'login-js.txt').read_text(encoding='utf8')
for needle in ('login','password','encrypt','account','LOGIN','api:'):
 print('\nLOGIN',needle)
 for m in list(re.finditer(re.escape(needle),s))[:4]:print(s[max(0,m.start()-120):m.end()+450])
