"""Use the authorized public SSO flow once; never log credentials or cookies."""
import requests,os,re,json,base64,sys,time
from html.parser import HTMLParser
from urllib.parse import urlsplit
from pathlib import Path
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.vault import ConfigVault

class Form(HTMLParser):
 def __init__(self):super().__init__();self.inside=False;self.values={};self.action=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=='form' and a.get('id')=='login-form':self.inside=True;self.action=a.get('action')
  if self.inside and tag=='input' and a.get('name'):self.values[a['name']]=a.get('value','')
 def handle_endtag(self,tag):
  if tag=='form':self.inside=False

c=requests.Session();c.trust_env=False;c.headers['User-Agent']='Mozilla/5.0'
r=c.get('https://noc.ruijie.com.cn/webproxy/sso/back',params={'rewrite':'off','back':'https://noc.ruijie.com.cn/macc5/admin/'},timeout=(4,15));r.encoding='utf8'
key=re.search(r'var apiserverRsa128EncryptPublickey\s*=\s*"([^"]+)"',r.text)
if key is None:raise RuntimeError('Public login key unavailable')
encrypted=base64.b64encode(PKCS1_v1_5.new(RSA.import_key(base64.b64decode(key[1]))).encrypt(os.environ['SHINYA_OFFICIAL_PASSWORD'].encode())).decode()
check=c.post('https://noc.ruijie.com.cn/sso/api/server/login/2factor',json={'method':'GET','requestParams':{'account':os.environ['SHINYA_OFFICIAL_ACCOUNT'],'password':encrypted}},timeout=(4,15)).json()
proof={'observedAt':time.time(),'twoFactorCode':check.get('code'),'twoFactorRequired':check.get('twoFactorCheck')}
print(json.dumps(proof),flush=True)
if check.get('code')!=0 or check.get('twoFactorCheck'):raise RuntimeError('Human verification required; no automated login retry')
if re.search(r'var isFirst\s*=\s*"false"',r.text):raise RuntimeError('Human captcha required; no automated login retry')
form=Form();form.feed(r.text)
if not form.values.get('lt') or not form.values.get('execution'):raise RuntimeError('Login form unavailable')
form.values.update(username=os.environ['SHINYA_OFFICIAL_ACCOUNT'],password=encrypted,originalPassword='',timeZone='Asia/Shanghai')
response=c.post(r.url,data=form.values,timeout=(4,20));proof.update(loginHost=urlsplit(response.url).hostname,loginPath=urlsplit(response.url).path)
api={'api':'/org/account/info','method':'GET','module':'default','querys':{'lang':'zh','cloudType':'smb'}}
info=c.post('https://noc.ruijie.com.cn/webproxy/common/api?/org/account/info',json=api,timeout=(4,15))
try:value=info.json()
except ValueError:value={}
proof.update(accountHttpStatus=info.status_code,accountCode=value.get('code'),accountFields=sorted(value))
if info.status_code==200 and value.get('code')==0:
 ConfigVault(Path('test/_keys/shinya-macc-session')).save({'cookies':[{'name':v.name,'value':v.value,'domain':v.domain,'path':v.path} for v in c.cookies],'capturedAt':time.time()})
 proof['encryptedSessionSaved']=True
Path('test/_analysis/oct10-priority/macc-session-check.json').write_text(json.dumps(proof,indent=2),encoding='utf8');print(json.dumps(proof),flush=True)
