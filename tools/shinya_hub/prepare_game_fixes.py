import os,shutil
from pathlib import Path
from remote_transport import connect_nas,run
ROOT=Path('test/_analysis/oct12-game-batch/fixes');BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(exist_ok=True)
c=connect_nas()
try:
    for name in ('game_environment.py','game_reports.py','game_report_list.js'):
        path='/opt/shinya-hub/shinya_hub/'+name
        (BASE/name).write_bytes(run(c,'docker exec shinya-hub cat '+path,sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        (BASE/('source-'+name)).write_bytes(run(c,'cat /volume1/docker/shinya-hub/src/shinya_hub/'+name))
finally:c.close()
shutil.copyfile('tools/shinya_hub/game_environment_source.py',OUT/'game_environment.py')
shutil.copyfile('tools/shinya_hub/game_reports_source.py',OUT/'game_reports.py')
s=(BASE/'game_report_list.js').read_text(encoding='utf8')
s=s.replace('t.$t("reportListHeader4")','"丢包率（%）"').replace('t.$t("reportTip2")','"仅展示最近一周、游戏时长大于5分钟的加速记录。"')
(OUT/'game_report_list.js').write_text(s,encoding='utf8')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Game fixes staged against installed baseline')
