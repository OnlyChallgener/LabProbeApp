"""Prepare only this batch against the captured, currently installed HUB."""
from pathlib import Path
import shutil
ROOT=Path('test/_analysis/oct11-device-controls')
BASE=ROOT/'baseline';OUT=ROOT/'deployment/shinya_hub'
OUT.mkdir(parents=True,exist_ok=True)
for name in ('api.py','projection.py','runtime.py','macc_channels.py','devices.py'):
 shutil.copyfile(BASE/name,OUT/name)
shutil.copyfile('tools/shinya_hub/station_controls_source.py',OUT/'station_controls.py')
def replace(name,old,new):
 p=OUT/name;s=p.read_text(encoding='utf8');assert s.count(old)==1,(name,old[:90]);p.write_text(s.replace(old,new),encoding='utf8')
replace('api.py','from .daily import CHINA',
 'from .station_controls import read_only5g, change_only5g, change_rate, check_rdp, event_history\nfrom .daily import CHINA')
replace('api.py','''        if (command.namespace, command.module) not in enabled_writes:
''','''        if (command.namespace, command.module) == ('dev_config','flowctrl_udp'):
            stations=station_snapshots(store,principal.tenant,selected_project(principal,raw))
            ips={str(s.get('ipaddr')) for s in stations if s.get('apSn')==key.sn or s.get('sn')==key.sn}
            return reply(json_string(change_rate(registry,key,command,ips)))
        if (command.namespace, command.module) not in enabled_writes:
''')
replace('api.py',"""                value=official_channels.request(key,resource,station_ip)
""","""                if resource=='remoteAccess/checkStaRdp':
                    return reply(check_rdp(registry,key,station_ip))
                value=official_channels.request(key,resource,station_ip)
""")
replace('api.py',"""                    project.update(apNum=len(project['apList']),staNum=len(stations),staCount=len(stations),
                                   staOnlineNum=sum(s['state']==1 for s in stations))""",
"""                    online_count=sum(s['state']==1 for s in stations)
                    project.update(apNum=len(project['apList']),totalCount=len(project['apList']),
                                   staNum=len(stations),staCount=len(stations),
                                   staOnlineNum=online_count,onlineStaCount=online_count)""")
p=OUT/'api.py';s=p.read_text(encoding='utf8')
a=s.index("            if resource == 'eSportAccelerate/getStaOnly5GList':")
b=s.index("            if resource == 'eSportAccelerate/setFlowCtrlMode':",a)
s=s[:a]+'''            if resource == 'eSportAccelerate/getStaOnly5GList':
                return reply(read_only5g(registry,selected_device(principal,raw)))

            if resource in {'eSportAccelerate/setOnly5GAndSendCmd', 'device/station/setOnly5G',
                            'station/setOnly5G', 'homeAp/setOnly5G'}:
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                if not principal.can_write:raise PermissionError('当前账号没有配置权限')
                key=selected_device(principal,raw)
                mac=station_mac(raw.get('staMac') or raw.get('mac'))
                stations=station_snapshots(store,principal.tenant,selected_project(principal,raw))
                if not any(mac_key(s['mac'])==mac and s.get('sn')==key.sn for s in stations):
                    raise PermissionError('只连5G目标不属于当前项目路由器')
                status=raw.get('isBlack',raw.get('enable',raw.get('status',raw.get('openStatus'))))
                if str(status).lower() not in ('0','1','true','false','on','off'):
                    raise ValueError('只连5G开关状态无效')
                return reply(change_only5g(registry,key,mac,str(status).lower() in ('1','true','on')))

''' +s[b:]
a=s.index("            if resource == 'device/station/getStaUpDownList':")
b=s.index('            if resource == "api/device/command/sendCmd":',a)
s=s[:a]+'''            if resource == 'device/station/getStaUpDownList':
                key=selected_device(principal,raw)
                mac=station_mac(raw.get('staMac') or raw.get('mac'))
                if not any(mac_key(s['mac'])==mac and s.get('sn')==key.sn
                           for s in station_snapshots(store,principal.tenant,selected_project(principal,raw))):
                    raise PermissionError('在网记录目标不属于当前项目路由器')
                return reply(event_history(registry,key,mac,raw.get('day')))

''' +s[b:]
p.write_text(s,encoding='utf8')
replace('projection.py',"""        cached_5g = store.cached(tenant, project['projectId'], sn, 'hub.esport.only5g')
        if cached_5g and isinstance(cached_5g.get('value'), list):
            for item in cached_5g['value']:
                if item.get('isBlack') == 1:
                    m = mac_key(item.get('staMac') or item.get('mac') or '')
                    if m:
                        only5g_macs.add(m)""", """        cached_5g=store.cached(tenant,project['projectId'],sn,
                              DeviceRegistry.resource(UFCommand('ac_config','get','apselect',None)))
        if cached_5g and isinstance(cached_5g.get('value'),dict):
            for item in cached_5g['value'].get('apselect_list',[]):
                if item.get('sel_band')=='5G' and item.get('mac'):
                    only5g_macs.add(mac_key(item['mac']))""")
replace('runtime.py','''                registry.cached_read(key, USER_LIST, ttl=30)''',
'''                registry.cached_read(key, UFCommand('ac_config','get','apselect',None), ttl=15)
                registry.cached_read(key, UFCommand('dev_sta','get','shinya_station',{'action':'history'}), ttl=15)
                registry.cached_read(key, USER_LIST, ttl=30)''')
replace('devices.py','''                record_resource(self.store,key,command,value,collected_at)''',
'''                record_resource(self.store,key,command,value,collected_at)
                if command.module=='shinya_station' and command.data=={'action':'history'}:
                    from .station_controls import record_events
                    record_events(self.store,key,value)''')
replace('macc_channels.py',"""        if str(value.get('targetSn')) != key.sn or str(value.get('remoteHost')) != host or int(value.get('remotePort', 0)) != 3389:""",
"""        identifiers={str(value[n]) for n in ('targetSn','remoteClient') if value.get(n)}
        if identifiers != {key.sn} or str(value.get('remoteHost')) != host or int(value.get('remotePort', 0)) != 3389:""")
print('Prepared device controls against installed source')
