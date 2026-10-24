"""Prepare the scan overlay against the current installed adapter, never old repo code."""
import os,shutil
from pathlib import Path
from remote_transport import connect_nas,run

ROOT=Path('test/_analysis/oct12-signal-tools/scan');BASE=ROOT/'baseline';OUT=ROOT/'deployment'
BASE.mkdir(parents=True,exist_ok=True);OUT.mkdir(parents=True,exist_ok=True)
c=connect_nas()
try:
    for name in ('api','hub_scan','signal_tools','official_channels','macc_channels'):
        (BASE/(name+'.py')).write_bytes(run(c,'docker exec shinya-hub cat /opt/shinya-hub/shinya_hub/'+name+'.py',sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
        path='/volume1/docker/shinya-hub/src/shinya_hub/'+name+'.py'
        (BASE/('source-'+name+'.py')).write_bytes(run(c,'if test -f '+path+'; then cat '+path+'; else printf __ABSENT_FILE__; fi'))
finally:c.close()
shutil.copyfile('tools/shinya_hub/signal_scan_source.py',OUT/'signal_scan.py')
for name in ('api','hub_scan'):shutil.copyfile(BASE/(name+'.py'),OUT/(name+'.py'))
p=OUT/'api.py';s=p.read_text(encoding='utf8')
s=s.replace('    from . import signal_tools','    from . import signal_tools\n    from .signal_scan import SignalScan\n    signal_scan=SignalScan(registry)\n    app.extensions["shinya_signal_scan"]=signal_scan')
a=s.index("            if resource in {'device/state/asyncChannelScan'")
b=s.index('            if resource == "experienceVisual/getSignalCover"',a)
s=s[:a]+'''            if resource in {'device/state/asyncChannelScan','state/asyncChannelScan'}:
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                if not principal.can_write:raise PermissionError('当前账号没有扫描权限')
                return reply(signal_scan.start(selected_device(principal,raw)))
            if resource in {'device/state/getLastScan','state/getLastScan'}:
                return reply(signal_scan.last(selected_device(principal,raw)))
''' +s[b:]
p.write_text(s,encoding='utf8')
p=OUT/'hub_scan.py';s=p.read_text(encoding='utf8')
s=s.replace('CHANNELS_58 = (149, 153, 157, 161, 165)','CHANNELS_58 = (149, 153, 157, 161)')
s=s.replace('            fraction = _overlap(entry, channel, channels)','            if entry.get("isSelfSsid"):\n                continue\n            fraction = _overlap(entry, channel, channels)')
s=s.replace('self_entry = bool(entry.get("isSelfSsid")) or ssid.casefold() in own','self_entry = bool(entry.get("isSelfSsid"))')
p.write_text(s,encoding='utf8')
print('Prepared real, project-scoped firmware scan; original H5 retained')
