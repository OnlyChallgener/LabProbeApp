"""Prepare a guarded overlay; reuse project-owned LabProbe engines."""
import os,shutil
from pathlib import Path
from remote_transport import connect_nas,run
ROOT=Path('test/_analysis/oct12-router-tools');BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(exist_ok=True)
c=connect_nas()
try:
    for name in ('api.py','router_tools_bridge.py'):
        path='/opt/shinya-hub/shinya_hub/'+name
        cmd="sh -c 'if test -f "+path+"; then cat "+path+"; else printf __ABSENT_FILE__; fi'"
        (BASE/name).write_bytes(run(c,'docker exec shinya-hub '+cmd,sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        path='/volume1/docker/shinya-hub/src/shinya_hub/'+name
        (BASE/('source-'+name)).write_bytes(run(c,'if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'))
finally:c.close()
s=(BASE/'api.py').read_text(encoding='utf8')
if "shinya_router_tools" not in s:
    marker='    from .signal_scan import SignalScan\n'
    assert s.count(marker)==1
    s=s.replace(marker,"    from .router_tools_bridge import RouterToolsBridge\n    router_tools=RouterToolsBridge(store,stun_bridge.bindings if stun_bridge else {})\n    app.extensions['shinya_router_tools']=router_tools\n\n"+marker)
    marker='            if resource == "experienceVisual/getSignalCover":\n'
    assert s.count(marker)==1
    branch="""            if resource == 'hub/routerTools':
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                key=selected_device(principal,raw)
                action=str(raw.get('action') or 'read')
                if action!='read' and not principal.can_write:raise PermissionError('当前账号没有配置权限')
                return reply(router_tools.request(key,str(raw.get('section') or ''),action,
                    str(raw.get('recordId') or ''),raw.get('draft')))
"""
    s=s.replace(marker,branch+marker)
old="""                return reply(router_tools.request(key,str(raw.get('section') or ''),action,
                    str(raw.get('recordId') or ''),raw.get('draft')))
"""
if old in s:
    s=s.replace(old,"""                try:
                    return reply(router_tools.request(key,str(raw.get('section') or ''),action,
                        str(raw.get('recordId') or ''),raw.get('draft')))
                except (ValueError,RuntimeError,ConnectionError) as error:
                    return reply(code=409,message=str(error))
""")
(OUT/'api.py').write_text(s,encoding='utf8')
shutil.copyfile('tools/shinya_hub/router_tools_bridge_source.py',OUT/'router_tools_bridge.py')
shutil.copyfile('tools/shinya_hub/staged_account/shinya_hub/local_service.py',OUT/'local_service.py')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Router tools staged against current installed source')
