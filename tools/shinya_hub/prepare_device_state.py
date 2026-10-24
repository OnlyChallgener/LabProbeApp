"""Repair the observed original UI contracts against the deployed control batch."""
from pathlib import Path
import shutil

ROOT=Path('test/_analysis/oct12-device-state')
BASE=ROOT/'baseline';OUT=ROOT/'deployment/shinya_hub'
OUT.mkdir(parents=True,exist_ok=True)
for file in BASE.glob('*.py'):
 if not file.name.startswith('source-'):shutil.copyfile(file,OUT/file.name)

def replace(name,old,new):
 p=OUT/name;s=p.read_text(encoding='utf8');assert s.count(old)==1,(name,old[:75])
 p.write_text(s.replace(old,new),encoding='utf8')

replace('station_controls.py','  return only5g_rows(value)',
        "  return [row['staMac'] for row in only5g_rows(value)]")

p=OUT/'station_controls.py';s=p.read_text(encoding='utf8')
a=s.index(' rows=command.data.get(\'list\')',s.index('def change_rate('))
b=s.index(' with registry.device_operation(key) as driver:',a)
s=s[:a]+''' deleting=command.action=='del'
 if deleting:
  names=command.data.get('rulename') if isinstance(command.data,dict) else None
  if not isinstance(names,list) or len(names)!=1 or not isinstance(names[0],str):
   raise ValueError('一次只能删除当前项目的一台终端限速')
 else:
  rows=command.data.get('list') if isinstance(command.data,dict) else None
  if not isinstance(rows,list) or len(rows)!=1 or not isinstance(rows[0],dict):
   raise ValueError('一次只能修改当前项目的一台终端限速')
  row=copy.deepcopy(rows[0])
''' +s[b:]
a=s.index("  matches=[v for v in current",s.index('def change_rate('))
b=s.index('  with registry._lock:',a)
s=s[:a]+'''  if deleting:
   matches=[v for v in current if v.get('comment')==names[0]]
   if len(matches)!=1:raise ValueError('限速规则已变化，请重新打开设置')
   row=matches[0]
  else:
   matches=[v for v in current if v.get('ipRange')==row.get('ipRange') and v.get('mode')=='per_ip']
  parts=str(row.get('ipRange','')).split('-')
  if len(parts)!=2 or parts[0]!=parts[1] or parts[0] not in station_ips:
   raise PermissionError('限速目标不属于当前项目终端')
  ipaddress.IPv4Address(parts[0])
  if row.get('mode')!='per_ip' or row.get('intf')!='br-wan':raise ValueError('终端限速规则类型无效')
  if not deleting:
   for name in ('upRate','downRate'):
    v=str(row.get(name,''))
    if not re.fullmatch(r'\\d+',v) or not 1<=int(v)<=10000000:raise ValueError('限速数值无效')
  if len(matches)>1:raise ValueError('该终端存在多条限速规则，请先核对')
  if command.action=='update' and not matches:raise ValueError('原限速规则已变化，请重新打开设置')
  if command.action=='add' and matches:raise ValueError('该终端已有规则，请重新打开设置')
  if matches and not deleting and str(row.get('pri'))!=str(matches[0].get('pri')):
   raise ValueError('限速规则标识已变化，请重新打开设置')
''' +s[b:]
p.write_text(s,encoding='utf8')

# The archived cloud identity must never keep a deleted rate-limit badge alive.
replace('projection.py',"        row['isOnly5G'] = is_5g_only",'''        rates=value(store,key,'dev_config','flowctrl_udp') or {}
        row['isRateLimited']=any(
            rule.get('mode')=='per_ip' and rule.get('enable')=='on'
            and rule.get('ipRange')==row['ipaddr']+'-'+row['ipaddr']
            and any(str(rule.get(n,'' )).isdigit() and int(rule[n])<10000000
                    for n in ('upRate','downRate'))
            for rule in rates.get('list',[]))
        row['isOnly5G'] = is_5g_only''')
replace('runtime.py',"                registry.cached_read(key, UFCommand('ac_config','get','apselect',None), ttl=15)",
'''                registry.cached_read(key, UFCommand('dev_config','get','flowctrl_udp',None), ttl=15)
                registry.cached_read(key, UFCommand('ac_config','get','apselect',None), ttl=15)''')

# The original renderer already has the short-disconnection line treatment.
replace('station_controls.py'," rows.reverse()\n return {'rows':rows,'list':rows}",
''' for index,row in enumerate(rows[:-1]):
  following=rows[index+1]
  if row['eventType']==2 and following['eventType']==1 and 0<=following['eventTime']-row['eventTime']<=300000:
   row['shortOff']=True
 rows.reverse()
 return {'rows':rows,'list':rows}''')
print('Prepared original UI state contracts')
