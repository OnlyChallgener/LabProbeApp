"""Bundle the existing HUB report UI with a project-scoped native API adapter."""
from pathlib import Path
import hashlib,json,re,requests

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'tools/shinya_hub/assets/hub-ui/scan-core'
PUBLIC='https://sy-oss-public.ruijie.com.cn/app-h5/'
runtime=(OUT/'script0.js').read_text(encoding='utf8')
assets=json.loads((OUT/'assets.json').read_text(encoding='utf8'))
def fetch(public,name):
    target=OUT/name
    if not target.exists():
        s=requests.Session();s.trust_env=False
        try:
            response=s.get(public,timeout=25);response.raise_for_status();target.write_bytes(response.content)
        finally:s.close()
    assets[public.split('?',1)[0]]=name
for chunk in (985,354,746):
    for match in re.finditer(r'(?:^|[,\{])'+str(chunk)+r':"([0-9a-f]+)"',runtime):
        digest=match[1]
        # The webpack runtime has separate JS/CSS dictionaries. Only existing
        # assets are retained, and unavailable CSS candidates are not mapped.
        for ext in ('js','css'):
            name=f'{chunk}.{digest}.{ext}'
            try:fetch(PUBLIC+f'static/{ext}/'+name,name)
            except requests.HTTPError as error:
                if error.response.status_code!=404:raise
for prefix,chunk in [('game_report_list',985),('game_report_detail',746)]:
    source=Path('D:/Github/ShinyaHub/shinya_hub',prefix+'.js').read_text(encoding='utf8')
    old='var c=l;' if prefix=='game_report_list' else 'var d=r;'
    assert source.count(old)==1
    source=source.replace(old, 'var '+('c' if prefix=='game_report_list' else 'd')+'=function(t){return window.HubGameRequest(t.url,t.data||{},t.method)};')
    names=[name for url,name in assets.items() if url.startswith(PUBLIC+f'static/js/{chunk}.')]
    assert len(names)==1,names
    (OUT/names[0]).write_text(source,encoding='utf8')
for item in ('image/game-vip.png','image/game-icon-back.png','image/game-default.png',
             'image/game-gamewifi.png','image/game-2.4.png','image/game-5.png','image/game-wired.png','image/game-wireless.png'):
    name=hashlib.sha256((PUBLIC+item).encode()).hexdigest()[:20]+'.png';fetch(PUBLIC+item,name)
(OUT/'assets.json').write_text(json.dumps(assets,ensure_ascii=False,indent=2),encoding='utf8')
source=(ROOT/'tools/shinya_hub/assets/hub-ui/official-scan.html').read_text(encoding='utf8')
source=source.replace('#/channelView?', '#/gameReportList?')
source=source.replace('<title>路由器扫描结果</title>','<title>游戏加速报告</title>')
source=source.replace("value.error||'扫描失败，请重试'", "value.error||'报告读取失败，请重试'")
start=source.index('window.HubScanRequest=')
end=source.index('window.receive=',start)
source=source[:start]+'''window.HubGameRequest=function(resource,body,method){return new Promise((resolve,reject)=>{
  const id=String(++sequence),timer=setTimeout(()=>{pending.delete(id);reject(new Error('报告请求超时，请重试'));},65000);
  pending.set(id,{resolve,reject,timer});HubGame.request(id,JSON.stringify({resource,body,method}));
});};
'''+source[end:]
(ROOT/'tools/shinya_hub/assets/hub-ui/game-reports.html').write_text(source,encoding='utf8')
print('Packaged HUB report list/details; API uses captured HUB connection, no fixed LAN URL')
