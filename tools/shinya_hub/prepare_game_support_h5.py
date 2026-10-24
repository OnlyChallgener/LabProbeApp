"""Keep original support/search UI, erase wishes and route reads through HUB."""
import hashlib,json,re,requests
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
CORE=ROOT/'tools/shinya_hub/assets/hub-ui/scan-core'
PUBLIC='https://sy-oss-public.ruijie.com.cn/app-h5/'
archive=ROOT/'test/_analysis/oct13-official-game/game-support';archive.mkdir(exist_ok=True)
assets=json.loads((CORE/'assets.json').read_text(encoding='utf8'))
for name in ('550.a7e4efb2.js','550.c31924eb.css'):
 p=archive/name
 if not p.exists():p.write_bytes((CORE/name).read_bytes())
 assets[PUBLIC+'static/'+('js/' if name.endswith('.js') else 'css/')+name]=name
source=(archive/'550.a7e4efb2.js').read_text(encoding='utf8')
start=source.index('e("div",{staticClass:"feedback-btn"')
end=source.index('-1===t.versionTs?',start)
source=source[:start]+source[end:]
start=source.index(',e("van-popup",{staticClass:"feedback-pop"')
end=source.index('],1)},n=[]',start)
source=source[:start]+source[end:]
source=source.replace(',showFeedBack:!1,information:""','')
start=source.index(',openFeedBack(){')
end=source.index('}}},l=c',start)
source=source[:start]+source[end+1:]
start=source.index(',e("van-button",{staticClass:"quick-btn"')
end=source.index('],1):e("div",t._l(t.gameTypeList',start)
source=source[:start]+source[end:]
source=source.replace('暂不支持该游戏加速，可反馈给官方以适配','暂未找到支持的游戏')
# The chunk's shared API factory is replaced by this page's one read endpoint.
start=source.index('23302:function(')
source=source[:start]+'''23302:function(t,e,a){a.d(e,{yo:function(){return function(t){return window.HubGameRequest("/api/v1/game/accelerate/getAccelerateGameList",t,"get")}}})}}]);'''
assert all(term not in source for term in ('游戏许愿','openFeedBack','quickFeedback','showFeedBack','gameReportFeedback'))
(CORE/'550.a7e4efb2.js').write_text(source,encoding='utf8')
css=(archive/'550.c31924eb.css').read_text(encoding='utf8')
css=re.sub(r'[^{}]*(?:feedback|quick-btn)[^{}]*\{[^{}]*\}','',css)
(CORE/'550.c31924eb.css').write_text(css,encoding='utf8')

# Public static game metadata is packaged; the service filters it against the
# current router's actual recognition library, with no game-cloud requests.
metadata=json.loads((archive/'reference-game-list.json').read_text(encoding='utf8'))['data']
session=requests.Session();session.trust_env=False
try:
 for game in metadata['gameList']:
  url=game['link'].replace('http://','https://',1)
  name='game-'+hashlib.sha256(url.encode()).hexdigest()[:20]+'.png'
  dest=CORE/name
  if not dest.exists():
   response=session.get(url,timeout=18);response.raise_for_status();dest.write_bytes(response.content)
  game['link']=url;assets[url]=name
finally:session.close()
(Path('D:/Github/ShinyaHub/shinya_hub/resources')/'supported_games.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf8')
(CORE/'assets.json').write_text(json.dumps(assets,ensure_ascii=False,indent=2),encoding='utf8')
html=(ROOT/'tools/shinya_hub/assets/hub-ui/game-reports.html').read_text(encoding='utf8').replace('#/gameReportList?','#/gameHelp?').replace('游戏加速报告','游戏支持说明').replace('报告请求超时','游戏列表请求超时').replace('报告读取失败','游戏列表读取失败')
(ROOT/'tools/shinya_hub/assets/hub-ui/game-support.html').write_text(html,encoding='utf8')
print('Packaged original support/search UI and static metadata; all game-wish code erased')
