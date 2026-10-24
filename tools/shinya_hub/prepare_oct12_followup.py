"""Capture current deployment and prepare narrowly scoped follow-up repairs."""
import os,shlex,shutil,json
from pathlib import Path
from remote_transport import connect_nas,run
ROOT=Path('test/_analysis/oct12-followup');BASE=ROOT/'baseline';OUT=ROOT/'deployment';BASE.mkdir(exist_ok=True);OUT.mkdir(exist_ok=True)
names=('api.py','runtime.py','station_controls.py','web_sync.py','floor_house.js')
c=connect_nas()
try:
 for name in names:
  installed='/opt/shinya-hub/shinya_hub/'+name;source='/volume1/docker/shinya-hub/src/shinya_hub/'+name
  for path,target,container in [(installed,BASE/name,True),(source,BASE/('source-'+name),False)]:
   command='if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'
   target.write_bytes(run(c,('docker exec shinya-hub sh -c '+shlex.quote(command)) if container else command,sudo_password=os.environ['SHINYA_NAS_PASSWORD'] if container else None))
finally:c.close()
for source,target in [('station_controls_source.py','station_controls.py'),('web_sync_source.py','web_sync.py')]:shutil.copyfile(Path('tools/shinya_hub')/source,OUT/target)
s=(BASE/'api.py').read_text(encoding='utf8')
old="    floor_bridge=FloorBridge(official_channels)";assert s.count(old)==1
s=s.replace(old,old+"\n    from .web_sync import WebSync\n    web_sync=WebSync(store,registry)\n    app.extensions['shinya_web_sync']=web_sync")
old="            if resource == 'device/station/getStaUpDownList':";assert s.count(old)==1
new="""            if resource in ('device/station/getStaRemarkSync','device/station/setStaRemarkSync'):
                key=selected_device(principal,raw)
                if resource.endswith('setStaRemarkSync'):
                    if not principal.can_write:raise PermissionError('当前账号没有同步名称的权限')
                    return reply(web_sync.set(key,raw.get('status')))
                return reply(web_sync.status(key))

"""
s=s.replace(old,new+old);(OUT/'api.py').write_text(s,encoding='utf8')
s=(BASE/'runtime.py').read_text(encoding='utf8');old='    app.extensions["shinya_children"].start(keys)';assert s.count(old)==1
s=s.replace(old,"    app.extensions['shinya_web_sync'].start(keys)\n    atexit.register(app.extensions['shinya_web_sync'].stop.set)\n"+old);(OUT/'runtime.py').write_text(s,encoding='utf8')
s=(BASE/'floor_house.js').read_text(encoding='utf8')
start=s.index('getHouseResult(){');end=s.index(',async loadInfer()',start)
method=s[start:end];start_body=method.index('if(0===e.code&&e.data){')+len('if(0===e.code&&e.data){');end_body=method.index('}else (t.hubResultError')
body=method[start_body:end_body]
new='''applyHubHouse(d){if(!d)return;const t=this,e={data:d};'''+body+''';window.HubFloor&&window.HubFloor.ready()},getHouseResult(){this.hubResultError=!1;try{const cached=window.HubFloor&&JSON.parse(window.HubFloor.cached());if(cached)this.applyHubHouse(cached)}catch(e){}const t=this;(0,c.g$)({projectId:this.projectId}).then((e=>{if(0===e.code&&e.data){t.applyHubHouse(e.data);window.HubFloor&&window.HubFloor.save(JSON.stringify(e.data))}else{t.hubResultError=!0;window.HubFloor&&window.HubFloor.ready();if(!t.hubResultReady)l.A.fail("获取渲染结果失败!")}})).catch((()=>{t.hubResultError=!0;window.HubFloor&&window.HubFloor.ready();if(!t.hubResultReady)l.A.fail("获取渲染结果失败!")}))}'''
s=s[:start]+new+s[end:];(OUT/'floor_house.js').write_text(s,encoding='utf8')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Prepared WEB name sync, station event ordering and immediate floor result cache')
