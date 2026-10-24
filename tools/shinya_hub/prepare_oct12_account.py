"""Capture and stage optional official binding plus observed-event repair."""
import os,shlex,shutil
from pathlib import Path
from remote_transport import connect_nas,run
ROOT=Path('test/_analysis/oct12-account');BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(exist_ok=True)
c=connect_nas()
try:
 for name in ('api.py','runtime.py','official_account.py','station_controls.py'):
  for path,target,inside in [('/opt/shinya-hub/shinya_hub/'+name,BASE/name,True),('/volume1/docker/shinya-hub/src/shinya_hub/'+name,BASE/('source-'+name),False)]:
   command='if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'
   target.write_bytes(run(c,'docker exec shinya-hub sh -c '+shlex.quote(command) if inside else command,sudo_password=os.environ['SHINYA_NAS_PASSWORD'] if inside else None))
finally:c.close()
for name in ('official_account','station_controls'):shutil.copyfile(Path('tools/shinya_hub')/(name+'_source.py'),OUT/(name+'.py'))
s=(BASE/'api.py').read_text(encoding='utf8')
old='official_channels=None, enabled_child_writes=None):';assert s.count(old)==1
s=s.replace(old,'official_channels=None, enabled_child_writes=None, official_account_directory=None):')
old='    floor_bridge=FloorBridge(official_channels)';assert s.count(old)==1
s=s.replace(old,old+"\n    from .official_account import OfficialAccount\n    official_account=OfficialAccount(official_channels,store,official_account_directory) if official_account_directory else None\n    app.extensions['shinya_official_account']=official_account")
old="            if resource == 'hub/stun':"
if old not in s:old='            if resource == "hub/stun":'
assert s.count(old)==1
s=s.replace(old,"""            if resource == 'hub/officialAccount':
                key=selected_device(principal,raw)
                if official_account is None:return reply(code=409,message='官方账号设置尚未启用')
                action=raw.get('action','read')
                if action=='read':return reply(official_account.status(key))
                if action!='bind' or request.method!='POST':return reply(code=400,message='官方账号操作无效')
                if not principal.can_write:raise PermissionError('当前账号没有绑定官方账号的权限')
                try:return reply(official_account.bind(key,raw.get('account'),raw.get('password')))
                except ValueError as error:return reply(code=409,message=str(error))

"""+old)
old='except OfficialChannelError as error:\n            return reply(code=error.code,message=str(error))';assert old in s
s=s.replace(old,'except OfficialChannelError as error:\n            return reply(code=409 if error.code in (401,403) else error.code,message=str(error))')
(OUT/'api.py').write_text(s,encoding='utf8')
s=(BASE/'runtime.py').read_text(encoding='utf8');old='official_channels=OfficialChannels(settings.get("officialBindings", [])))';assert old in s
s=s.replace(old,'official_channels=OfficialChannels(settings.get("officialBindings", [])),\n                     official_account_directory=data_directory / "official-accounts")')
(OUT/'runtime.py').write_text(s,encoding='utf8')
for p in OUT.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Prepared optional account service and station-source repair')
