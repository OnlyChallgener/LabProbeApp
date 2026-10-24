"""Read-only original UI contract checks against the independent live HUB."""
import json,os,sys
from pathlib import Path
from remote_transport import connect_nas,run

CODE=r'''
import json,requests,traceback
from datetime import datetime,timedelta
from zoneinfo import ZoneInfo
s=requests.Session();s.trust_env=False
base='http://127.0.0.1:58543/api/v1/'
v=s.post(base+'base/homeUser/accountPasswordLogin',json={'account':ACCOUNT,'password':PASSWORD},timeout=10).json()
assert v['code']==0
s.headers['Authorization']='Bearer '+(v['data'].get('token') or v['data'].get('accessToken'))
def post(path,p):
 v=s.post(base+path,json=p,timeout=20).json()
 assert v['code']==0,(path,v.get('msg'))
 q={**p,'meshApSnList':'G1TD8RX039025','mainApCreateTime':'2026-10-11 22:24:10'}
 g=s.get(base+path,params=q,timeout=20).json()
 assert g['code']==0,(path,g.get('msg'))
 assert g['data']==v['data'],('GET differs from POST',path)
 return v['data']
day=str(datetime.now(ZoneInfo('Asia/Shanghai')).date())
baseparams={'mainApSn':'G1TD8RX039025'}
members=post('netguard/staGuard/getProtectStaListPage',baseparams)['rows']
assert len(members)==6
proof={'members':len(members),'day':day,'devices':[]}
for user in members:
 p={**baseparams,'staId':user['staId'],'day':day}
 hourly=post('netguard/onlineReport/getStaOnlineTimeDistPerHour',p)['rows']
 daily=post('netguard/onlineReport/getStaOnlineTimeDistPerDay',p)
 alarms=post('netguard/onlineReport/getAlarmEventLogsBySta',p)['rows']
 apps=post('netguard/onlineReport/getStaAppReport',p)['rows']
 disable=post('netguard/staGuard/getAppTimePlanDisable',p)['disable']
 assert len(hourly)==24 and all(isinstance(n,(int,float)) for n in hourly)
 assert len(daily['weekList'])==len(daily['timeList'])==10
 assert sum(hourly)==daily['timeList'][-1]==user['todaySurfMinutes']
 assert len(alarms)==10 and alarms[0]['day']==day
 assert all(r['eventList'] and all('detail' in e and e['level'] in (0,2,3) for e in r['eventList']) for r in alarms)
 proof['devices'].append({'staId':user['staId'],'name':user['remark'],'today':sum(hourly),'weekly':daily,
  'alarms':alarms,'apps':apps,'planDisable':disable})
print(json.dumps(proof,ensure_ascii=False),flush=True)
'''

def main():
    code=CODE.replace('ACCOUNT',repr(os.environ['SHINYA_ADMIN_ACCOUNT'])).replace('PASSWORD',repr(os.environ['SHINYA_ADMIN_PASSWORD']))
    code='try:\n'+''.join('    '+line+'\n' for line in code.splitlines())+'except Exception:\n    import traceback\n    print(traceback.format_exc(),flush=True)\n'
    client=connect_nas()
    try:
        output=run(client,'docker exec -i shinya-hub python -',stdin=code.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=150)
        proof=json.loads(output)
        Path('test/_analysis/oct11-children/live-ui-contracts.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps({'members':proof['members'],'day':proof['day'],'devices':[
            {'name':r['name'],'today':r['today'],'days':len(r['weekly']['timeList']),
             'alertDays':sum(any(e['level']>0 for e in d['eventList']) for d in r['alarms']),
             'planDisable':r['planDisable'],'apps':len(r['apps'])} for r in proof['devices']]},ensure_ascii=False),flush=True)
    except Exception:
        print(output.decode(errors='replace')[:1800] if 'output' in locals() else 'Live contract check failed',flush=True)
        raise
    finally:client.close()

if __name__=='__main__':main()
