"""User-approved read-only signal contract comparison for BE72 and BE50."""
import hashlib,json,os,sys
from pathlib import Path
import requests
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.vault import ConfigVault
from audit_game_environment import clean
out=Path('test/_analysis/oct12-signal-coverage');out.mkdir(exist_ok=True)
data=ConfigVault(Path('test/_keys/shinya-official-account')).load()
assert data['accountHash']==hashlib.sha256(os.environ['SHINYA_OFFICIAL_ACCOUNT'].encode()).hexdigest()
s=requests.Session();s.trust_env=False
for pid,sn in [('18850348','G1TD8RX039025'),('17953001','G1TQ88T702111')]:
    result={}
    for path in ('experienceVisual/getSignalCover','homeAp/getStaList2'):
        v=s.post('https://shinyaapp.ruijie.com.cn/mvp/homewlan/'+path,
            json={'projectId':pid,'sn':sn,'apSn':sn},headers={'Authorization':'Bearer '+data['token'],
            'type':'shinyaApp','projectId':pid},timeout=25,allow_redirects=False).json()
        result[path]=clean(v)
        print(pid,path,'code',v.get('code'),flush=True)
    (out/(pid+'-official.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
s.close()
