"""Capture the installed/source baselines before the scoped UI/data repair."""
import os,shutil
from pathlib import Path
from remote_transport import connect_nas,run

ROOT=Path('test/_analysis/oct12-controls-finish')
BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(exist_ok=True)
names=('api.py','router_tools_bridge.py','station_identity.py','signal_coverage.py','floor_house.js')
c=connect_nas()
try:
    for name in names:
        path='/opt/shinya-hub/shinya_hub/'+name
        cmd="sh -c 'if test -f "+path+"; then cat "+path+"; else printf __ABSENT_FILE__; fi'"
        (BASE/name).write_bytes(run(c,'docker exec shinya-hub '+cmd,sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        path='/volume1/docker/shinya-hub/src/shinya_hub/'+name
        (BASE/('source-'+name)).write_bytes(run(c,'if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'))
finally:c.close()

for source,target in [('router_tools_bridge_source.py','router_tools_bridge.py'),
                      ('staged_identity/station_identity.py','station_identity.py'),
                      ('signal_coverage_source.py','signal_coverage.py')]:
    shutil.copyfile(Path('tools/shinya_hub')/source,OUT/target)

s=(BASE/'api.py').read_text(encoding='utf8')
marker='        \'<script defer="defer" src="/app-h5-hub/game_report_list.js"></script>\''
assert s.count(marker)==1
s=s.replace(marker,'        \'<script defer="defer" src="/app-h5-hub/floor_house.js"></script>\'\n'+marker)
marker='        \'<title>星耀家</title>\''
css='''.house-reduction .icon-circle-plus{display:inline-block!important;width:44px;height:44px;border-radius:50%;background:#25bcbf;font-size:0!important;position:relative;vertical-align:middle}.house-reduction .icon-circle-plus:before,.house-reduction .icon-circle-plus:after{content:""!important;position:absolute;left:23%;top:46%;width:54%;height:8%;border-radius:4px;background:white}.house-reduction .icon-circle-plus:after{transform:rotate(90deg)}.hub-house-loading{min-height:250px;display:flex;align-items:center;justify-content:center}'''
assert s.count(marker)==1
s=s.replace(marker,"        '<style>"+css+"</style>'\n"+marker)
marker="if name not in {'game_report_list.js','game_report_detail.js'}:"
assert s.count(marker)==1
s=s.replace(marker,"if name not in {'game_report_list.js','game_report_detail.js','floor_house.js'}:")
(OUT/'api.py').write_text(s,encoding='utf8')

original=Path('test/_analysis/oct10-accounts/official-h5/942.dc3ba916.js').read_text(encoding='utf8')
s=original
anchor='var i=function(){var t=this,e=t._self._c;return e('
assert s.count(anchor)==1
gate='''var i=function(){var t=this,e=t._self._c;if(!t.hubResultReady)return e("div",{staticClass:"white-card hub-house-loading"},[t.hubResultError?e("van-button",{attrs:{round:true,color:"#25bcbf"},on:{click:t.getHouseResult}},[t._v("重新加载")]):e("van-loading",{attrs:{color:"#25bcbf",size:28}})]);return e('''
s=s.replace(anchor,gate)
anchor='return{projectId:null,token:null,';assert s.count(anchor)==1
s=s.replace(anchor,'return{hubResultReady:!1,hubResultError:!1,projectId:null,token:null,')
start=s.index('getHouseResult(){');end=s.index(',async loadInfer()',start)
method=s[start:end]
method=method.replace('getHouseResult(){const t=this;', 'getHouseResult(){this.hubResultError=!1;const t=this;')
assert method.count('if(0===e.code){')==1
method=method.replace('if(0===e.code){','if(0===e.code&&e.data){t.hubResultReady=!0,')
assert method.count('l.A.fail("获取渲染结果失败!")')==2
method=method.replace('l.A.fail("获取渲染结果失败!")','(t.hubResultError=!0,l.A.fail("获取渲染结果失败!"))')
s=s[:start]+method+s[end:]
(OUT/'floor_house.js').write_text(s,encoding='utf8')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Current source captured; identity, router settings, coverage and floor UI staged')
