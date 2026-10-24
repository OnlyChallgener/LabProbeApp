"""Read firmware UU implementation and a reference-only public game catalogue."""
import json,os
from pathlib import Path
from remote_transport import connect_nas,connect_router,run
OUT=Path('test/_analysis/oct13-official-game/game-support');OUT.mkdir(exist_ok=True)
CODE=r'''
import json,requests
from pathlib import Path
from shinya_hub.vault import ConfigVault
saved=ConfigVault(Path('/data/official-account')).load()
b=next(r for r in saved['devices'] if r['sn']=='G1TD8RX039025')
c=next(r for r in saved['credentials'] if r['tenant']==b['tenant'])
s=requests.Session();s.trust_env=False
v=s.post('https://shinyaapp.ruijie.com.cn/api/v1/base/homeUser/accountPasswordLogin',json={'account':c['account'],'password':c['password']},timeout=18).json()
assert v.get('code')==0,'Reference login failed'
s.headers.update({'Authorization':'Bearer '+v['data']['token'],'type':'shinyaApp','projectId':b['officialProjectId']})
v=s.get('https://shinyaapp.ruijie.com.cn/api/v1/game/accelerate/getAccelerateGameList',params={'sn':b['sn']},timeout=25).json()
print(json.dumps(v,ensure_ascii=False))
'''
c=connect_nas()
try:
 value=run(c,'docker exec -i shinya-hub python -',stdin=CODE.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=55)
 (OUT/'reference-game-list.json').write_bytes(value)
 data=json.loads(value);print('Reference support list',data.get('code'),len((data.get('data') or {}).get('gameList',[])))
finally:c.close()
c=connect_router('be72')
try:
 assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']=='G1TD8RX039025'
 for name,cmd in [('uu-native.lua','cat /usr/local/lua/dev_sta/uu_acc.lua'),('uu-native-state.json',"dev_sta get -m uu_acc '{\"option\":\"status\"}'"),('game-library.json','cat /usr/share/ndpi/db.default.json'),('low-rate-methods.txt','ubus -v list low_rate')]:
  try:
   value=run(c,cmd,timeout=20);(OUT/name).write_bytes(value);print(name,len(value))
  except Exception as e:print(name,type(e).__name__)
finally:c.close()
