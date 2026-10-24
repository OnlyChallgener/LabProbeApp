"""Replace only inspected wireless branches in the current installed adapter."""
from pathlib import Path
import shutil

ROOT=Path('test/_analysis/oct12-signal-tools');BASE=ROOT/'baseline/shinya_hub';OUT=ROOT/'deployment/shinya_hub'
OUT.mkdir(parents=True,exist_ok=True)
for name in ('api','runtime'):shutil.copyfile(BASE/(name+'.py'),OUT/(name+'.py'))
for name in ('signal_schedule','signal_tools'):shutil.copyfile(Path('tools/shinya_hub')/(name+'_source.py'),OUT/(name+'.py'))

p=OUT/'api.py';s=p.read_text(encoding='utf8')
s=s.replace('    enabled_writes = enabled_writes or set()',
'''    enabled_writes = enabled_writes or set()
    from . import signal_tools
''',1)
anchor="        if (command.namespace, command.module) == ('dev_config','flowctrl_udp'):"
assert s.count(anchor)==1
s=s.replace(anchor,"""        if (command.namespace, command.module) == ('dev_config','radio'):
            return reply(json_string(signal_tools.power(registry,key,command.data)))
"""+anchor)
a=s.index("            if resource in {'device/manage/wireless/timeManage/list'")
b=s.index('            if resource in {\'device/state/asyncChannelScan\'',a)
s=s[:a]+'''            if resource in {'device/manage/wireless/timeManage/list','manage/wireless/timeManage/list'}:
                return reply(signal_tools.wireless_state(registry,selected_device(principal,raw)))
            if resource in {'device/manage/wireless/timeManage/detail','manage/wireless/timeManage/detail'}:
                return reply(signal_tools.wireless_state(registry,selected_device(principal,raw),raw.get('wlanId')))
            if resource in {'device/manage/wifi/timeManage','manage/wifi/timeManage'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not principal.can_write:raise PermissionError('当前账号没有配置权限')
                return reply(signal_tools.change_wireless(registry,selected_device(principal,raw),raw))
            if resource in {'device/manage/wireless/healthy/cfg','manage/wireless/healthy/cfg',
                            'device/manage/wireless/heathy/cfg','manage/wireless/heathy/cfg'}:
                key=selected_device(principal,raw)
                if request.method=='GET':return reply(signal_tools.wireless_state(registry,key,healthy=True))
                if not principal.can_write:raise PermissionError('当前账号没有配置权限')
                return reply(signal_tools.change_wireless(registry,key,raw,healthy=True))
            if resource in {'device/channel/getLastChannelOptimizeResult','channel/getLastChannelOptimizeResult'}:
                return reply(signal_tools.current(registry,selected_device(principal,raw)))
            if resource in {'device/channel/getChannelOptimizeCronJob','channel/getChannelOptimizeCronJob'}:
                return reply(signal_tools.cron(store,selected_device(principal,raw)))
            if resource in {'device/channel/setChannelOptimizeCronJob','channel/setChannelOptimizeCronJob'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not principal.can_write:raise PermissionError('当前账号没有配置权限')
                return reply(signal_tools.cron(store,selected_device(principal,raw),raw))
            if resource in {'device/channel/channelOptimize','channel/channelOptimize'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not principal.can_write:raise PermissionError('当前账号没有优化权限')
                return reply(signal_tools.optimize(registry,selected_device(principal,raw),str(raw.get('bandStr') or '2.4G')))
''' +s[b:]
# Remove the project-ID rewrites and fallback: the original H5 receives the
# numeric ID of the selected Hub project through the already-patched bundle.
a=s.index("        '<script>'",s.index('    APP_H5_HTML'))
b=s.index("        '<script defer=",a)
s=s[:a]+s[b:]
p.write_text(s,encoding='utf8')
p=OUT/'runtime.py';s=p.read_text(encoding='utf8')
s=s.replace('    stopping = threading.Event()',
'''    stopping = threading.Event()
    from .signal_tools import ChannelTimer
    channel_timer=ChannelTimer(registry)''')
s=s.replace('            for key in order:\n                registry.cached_read(key, UFCommand("dev_sta", "get", "sysinfo", None), ttl=30)',
'''            channel_timer.tick(order)
            for key in order:
                registry.cached_read(key, UFCommand("dev_sta", "get", "sysinfo", None), ttl=30)''')
s=s.replace('        stopping.set()','        stopping.set()\n        channel_timer.close()')
p.write_text(s,encoding='utf8')
print('Prepared native wireless operations; no fixed channel/results or swallowed writes')
