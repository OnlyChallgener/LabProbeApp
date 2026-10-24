"""Stage the game batch against the exact installed/source baselines."""
import os,shutil,json,re
from pathlib import Path
from remote_transport import connect_nas,run

ROOT=Path('test/_analysis/oct12-game-batch');BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(parents=True,exist_ok=True)
c=connect_nas()
try:
    for name in ('api','runtime','game_environment','game_acceleration','game_reports'):
        path='/opt/shinya-hub/shinya_hub/'+name+'.py'
        (BASE/(name+'.py')).write_bytes(run(c,'docker exec shinya-hub sh -c '+"'if test -f "+path+'; then cat '+path+"; else printf __ABSENT_FILE__; fi'",sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        path='/volume1/docker/shinya-hub/src/shinya_hub/'+name+'.py'
        (BASE/('source-'+name+'.py')).write_bytes(run(c,'if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'))
finally:c.close()
for name in ('game_environment','game_acceleration','game_reports'):
    shutil.copyfile('tools/shinya_hub/'+name+'_source.py',OUT/(name+'.py'))
s=(BASE/'api.py').read_text(encoding='utf8')
anchor="    app.extensions['shinya_game_environment']=game_environment"
assert s.count(anchor)==1
s=s.replace(anchor,anchor+"\n    from . import game_acceleration\n    from .game_reports import GameReports\n    game_reports=GameReports(store,registry,catalog)\n    app.extensions['shinya_game_reports']=game_reports")
anchor='    @app.route("/game/<path:resource>", methods=["GET", "POST"])'
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'\n    @app.route("/oneStepGame/<path:resource>", methods=["GET", "POST"])')
anchor='        elif request.path.startswith("/game/"):'
s=s.replace(anchor,'        elif request.path.startswith("/oneStepGame/"):\n            resource = "oneStepGame/" + resource\n'+anchor)
start=s.index("            if resource == 'eSportAccelerate/getEsportAccelerateStatus':")
end=s.index("            if resource == 'eSportAccelerate/getStaOnly5GList':",start)
s=s[:start]+'''            if resource in {'oneStepGame/startFastAccelerate','eSportAccelerate/openESportAccelerate','eSportAccelerate/closeESportAccelerate'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not principal.can_write:raise PermissionError('当前账号没有配置权限')
                key=selected_device(principal,raw)
                opening=resource!='eSportAccelerate/closeESportAccelerate'
                value=game_acceleration.change(registry,key,opening)
                if not opening:game_reports.finish(key)
                return reply(value)
            if resource=='eSportAccelerate/getEsportAccelerateStatus':
                return reply(game_acceleration.status(registry,selected_device(principal,raw)))
            if resource in {'eSportAccelerate/getGameAccelerateHistoryNew','eSportAccelerate/getGameAccelerateHistory','eSportAccelerate/getIsGaming',
                            'game/accelerate/getGameStatistics','game/accelerate/getGameLog','game/accelerate/getAccelerateStatistics',
                            'eSportAccelerate/getHistoryDetail','eSportAccelerate/gameReportClickCount','eSportAccelerate/gameReportFeedback'}:
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                key=selected_device(principal,raw)
                if resource.endswith('getHistoryDetail'):return reply(game_reports.detail(key,raw.get('uuid')))
                if resource.endswith('gameReportClickCount'):return reply(None)
                if resource.endswith('gameReportFeedback'):
                    if not principal.can_write:raise PermissionError('当前账号没有反馈权限')
                    uuid=raw.get('uuid') or raw.get('eventId');game_reports.detail(key,uuid)
                    with store.connect() as db:
                        db.execute('INSERT INTO game_feedback VALUES(?,?,?,?,?,?)',(key.tenant,key.project,key.sn,str(uuid),time.time(),json.dumps(raw,ensure_ascii=False)))
                    return reply(None)
                if resource.endswith('getGameStatistics'):return reply(game_reports.statistics(key))
                if resource.endswith('getGameLog'):return reply(game_reports.logs(key))
                if resource.endswith('getAccelerateStatistics'):return reply({'gameLogDetailList':game_reports.logs(key)['gameLogDetailList'],'source':'hub-firmware-rounds'})
                rows=game_reports.list(key,raw.get('includeInGame',True))
                if resource.endswith('getIsGaming'):rows=[r for r in rows if r['inGaming']>0]
                return reply(rows)

''' +s[end:]
# Public page assets retain the original layouts. Nullable observations need
# rendering guards, and the original empirical delay model must not transform
# this HUB's already measured ICMP latency into an invented game estimate.
def asset(chunk):return next(Path('test/_analysis/oct12-game-reports').glob(str(chunk)+'.*js')).read_text(encoding='utf8')
listing=asset(985)
for expr,n in [('a.avgDelay',0),('a.lossRate',1)]:
    old=f'{expr}.toFixed({n})';assert old in listing
    listing=listing.replace(old,f'(typeof {expr}==="number"?{old}:"--")')
listing=listing.replace('e.avgDelay=t.$utils.reviseDelay(e.avgDelay)','e.avgDelay=e.avgDelay')
listing=listing.replace('getLevel(t,e,a){return','getLevel(t,e,a){if(typeof e!=="number")return "neutral";return')
listing=listing.replace('t.$t("reportListHeader4")','"丢包率（%）"')
listing=listing.replace('t.$t("reportTip2")','"仅展示最近一周、游戏时长大于5分钟的加速记录。"')
detail=asset(746)
for expr,n in [('t.detailInfo.avgDelay',1),('t.detailInfo.lossRate',1),('t.detailInfo.jitterRate',1)]:
    old=f'{expr}.toFixed({n})';assert old in detail
    detail=detail.replace(old,f'(typeof {expr}==="number"?{old}:"--")')
detail=detail.replace('avgDelay:t.$utils.reviseDelay(e.data.avgDelay)','avgDelay:e.data.avgDelay')
# The original percentile is a fixed interpolation, not measured HUB players.
detail=detail.replace('e("span",{staticClass:"small-white"},[t._v(" 超越 "+t._s(t.defeatPercent(t.detailInfo.avgDelay).toFixed(1))+"% 的玩家 ")])','t._e()')
assert ' 超越 ' not in detail
detail=detail.replace('卡帧率','丢包率')
(OUT/'game_report_list.js').write_text(listing,encoding='utf8')
(OUT/'game_report_detail.js').write_text(detail,encoding='utf8')
anchor='        \'<script defer="defer" src="https://sy-oss-public.ruijie.com.cn/app-h5/static/js/app.6df7475f.js"></script>\''
assert s.count(anchor)==1
s=s.replace(anchor,'        \'<script defer="defer" src="/app-h5-hub/game_report_list.js"></script>\'\n        \'<script defer="defer" src="/app-h5-hub/game_report_detail.js"></script>\'\n'+anchor)
anchor='    @app.route("/app-h5", methods=["GET"])'
s=s.replace(anchor,'''    @app.route('/app-h5-hub/<name>',methods=['GET'])
    def game_h5_asset(name):
        from pathlib import Path
        if name not in {'game_report_list.js','game_report_detail.js'}:return '',404
        value=(Path(__file__).parent/name).read_text(encoding='utf8')
        return app.response_class(value,mimetype='application/javascript; charset=utf-8')

'''+anchor)
(OUT/'api.py').write_text(s,encoding='utf8')
s=(BASE/'runtime.py').read_text(encoding='utf8')
anchor='    app.extensions["shinya_children"].start(keys)';assert s.count(anchor)==1
s=s.replace(anchor,anchor+'\n    app.extensions["shinya_game_reports"].start(keys)')
anchor='        app.extensions["shinya_game_environment"].close()';assert s.count(anchor)==1
s=s.replace(anchor,anchor+'\n        app.extensions["shinya_game_reports"].close()')
(OUT/'runtime.py').write_text(s,encoding='utf8')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Game batch staged and compiled')
