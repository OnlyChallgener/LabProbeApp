"""User-approved read-only report contract comparison for BE72 and BE50."""
import hashlib,json,os,sys
from pathlib import Path
import requests
sys.path.insert(0,'D:/Github/ShinyaHub')
from shinya_hub.vault import ConfigVault
from audit_game_environment import clean
out=Path('test/_analysis/oct12-game-reports');out.mkdir(exist_ok=True)
session=requests.Session();session.trust_env=False
data=ConfigVault(Path('test/_keys/shinya-official-account')).load()
assert data['accountHash']==hashlib.sha256(os.environ['SHINYA_OFFICIAL_ACCOUNT'].encode()).hexdigest()
headers={'Authorization':'Bearer '+data['token'],'type':'shinyaApp'}
for pid,sn in [('18850348','G1TD8RX039025'),('17953001','G1TQ88T702111')]:
    result={}
    for path,payload in [('/api/v1/game/accelerate/getGameStatistics',{'sn':sn}),
                         ('/api/v1/game/accelerate/getGameLog',{'sn':sn}),
                         ('/mvp/homewlan/eSportAccelerate/getGameAccelerateHistoryNew',{'projectId':pid,'includeInGame':True})]:
        response=session.post('https://shinyaapp.ruijie.com.cn'+path,json=payload,
            headers={**headers,'projectId':pid},timeout=25,allow_redirects=False)
        value=response.json();result[path]=clean(value)
        print(pid,path,'code',value.get('code'),'data type',type(value.get('data')).__name__,flush=True)
    (out/(pid+'-contracts.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
