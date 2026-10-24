import os,shutil
from pathlib import Path
from remote_transport import connect_nas,run

ROOT=Path('test/_analysis/oct12-signal-tools/complete');BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(parents=True,exist_ok=True)
c=connect_nas()
try:
    for name in ('api','signal_tools','hub_scan','official_channels','signal_scan'):
        (BASE/(name+'.py')).write_bytes(run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/'+name+'.py',sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        path='/volume1/docker/shinya-hub/src/shinya_hub/'+name+'.py'
        (BASE/('source-'+name+'.py')).write_bytes(run(c,'if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'))
finally:c.close()
shutil.copyfile('tools/shinya_hub/floor_bridge_source.py',OUT/'floor_bridge.py')
shutil.copyfile('tools/shinya_hub/signal_tools_source.py',OUT/'signal_tools.py')
shutil.copyfile('tools/shinya_hub/signal_scan_source.py',OUT/'signal_scan.py')
s=(BASE/'api.py').read_text(encoding='utf8')
s=s.replace('    from . import signal_tools','    from . import signal_tools\n    from .floor_bridge import FloorBridge, PREFIX as FLOOR_PREFIX, WRITES as FLOOR_WRITES\n    floor_bridge=FloorBridge(official_channels)')
pos=s.index('            if child_handles(resource):')
s=s[:pos]+'''            if resource.startswith(FLOOR_PREFIX):
                leaf=resource.removeprefix(FLOOR_PREFIX)
                if leaf in FLOOR_WRITES and request.method!='POST':return reply(code=405,message='保存请求方式无效')
                if leaf not in FLOOR_WRITES and request.method!='GET':return reply(code=405,message='读取请求方式无效')
                if (leaf in FLOOR_WRITES or leaf=='getImageUploadSignature') and not principal.can_write:
                    raise PermissionError('当前账号没有户型配置权限')
                key=selected_device(principal,raw)
                result=floor_bridge.request(key,resource,raw)
                return app.json.response(result)
''' +s[pos:]
(OUT/'api.py').write_text(s,encoding='utf8')
print('Prepared original H5 floor protocol behind the current project binding')
