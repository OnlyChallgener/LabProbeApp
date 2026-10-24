"""Prepare a small overlay from the currently installed independent HUB."""
import os
import shutil
from pathlib import Path
from remote_transport import connect_nas,run

ROOT=Path('test/_analysis/oct12-game-environment')
BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(exist_ok=True);OUT.mkdir(exist_ok=True)
c=connect_nas()
try:
    for name in ('api','runtime','game_environment'):
        path='/opt/shinya-hub/shinya_hub/'+name+'.py'
        (BASE/(name+'.py')).write_bytes(run(c,'docker exec shinya-hub sh -c '+"'if test -f "+path+'; then cat '+path+"; else printf __ABSENT_FILE__; fi'",sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        path='/volume1/docker/shinya-hub/src/shinya_hub/'+name+'.py'
        (BASE/('source-'+name+'.py')).write_bytes(run(c,'if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'))
finally:c.close()
shutil.copyfile('tools/shinya_hub/game_environment_source.py',OUT/'game_environment.py')
s=(BASE/'api.py').read_text(encoding='utf8')
anchor='    enabled_child_writes = enabled_child_writes or set()'
assert s.count(anchor)==1
s=s.replace(anchor,"    from .game_environment import GameEnvironment\n    game_environment=GameEnvironment(registry,store)\n    app.extensions['shinya_game_environment']=game_environment\n\n"+anchor)
anchor='    @app.route("/netguard/<path:resource>", methods=["GET", "POST"])'
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'\n    @app.route("/game/<path:resource>", methods=["GET", "POST"])')
anchor='        elif request.path.startswith("/netguard/"):'
assert s.count(anchor)==1
s=s.replace(anchor,'        elif request.path.startswith("/game/"):\n            resource = "game/" + resource\n'+anchor)
anchor="            if resource == 'eSportAccelerate/getEsportAccelerateStatus':"
assert s.count(anchor)==1
s=s.replace(anchor,"""            if resource == 'game/accelerate/getCurrentGameEnv':
                if request.method!='POST':return reply(code=405,message='读取请求方式无效')
                return reply(game_environment.current(selected_device(principal,raw)))
            if resource.startswith('game/gameEnvCheck/'):
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                key=selected_device(principal,raw)
                leaf=resource.removeprefix('game/gameEnvCheck/')
                if leaf=='startCheck':return reply(game_environment.start(key))
                if leaf=='getLastCheckDetail':return reply(game_environment.last(key))
                if leaf in {'getCheckDetail','getOptimizeDetail'}:
                    return reply(game_environment.detail(key,raw.get('checkId')))
                if leaf=='startOptimize':
                    if not principal.can_write:raise PermissionError('当前账号没有优化配置权限')
                    return reply(game_environment.optimize(key,raw.get('checkId')))
                return reply(code=404,message='未找到游戏环境接口')

"""+anchor)
(OUT/'api.py').write_text(s,encoding='utf8')
s=(BASE/'runtime.py').read_text(encoding='utf8')
anchor='        app.extensions["shinya_diagnosis"].close()'
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'\n        app.extensions["shinya_game_environment"].close()')
(OUT/'runtime.py').write_text(s,encoding='utf8')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Game environment overlay prepared and compiled')
