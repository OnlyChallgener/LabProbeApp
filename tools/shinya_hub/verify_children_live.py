"""Test only the authorized BE72 PC, always restore native and private rules."""
import os,sys,json
from pathlib import Path
from remote_transport import connect_nas,run

CODE=r'''
import copy,json,time,requests
from pathlib import Path
from datetime import datetime,timedelta
from zoneinfo import ZoneInfo
from shinya_hub.vault import ConfigVault
from shinya_hub.devices import DeviceDriver,DeviceKey
from shinya_hub.commands import UFCommand
from shinya_hub.store import ProjectStore
from shinya_hub.children import ChildrenService,STATE,CONFIG
from shinya_hub.catalog import AppCatalog
UID='9A59FF88998744D4B7B4FB05E3E0255C'
settings=ConfigVault(Path('/config')).load()
device=next(d for d in settings['devices'] if d['sn']=='G1TD8RX039025')
key=DeviceKey(device['tenant'],device['project'],device['sn'])
driver=DeviceDriver(device['address'],device['password'],device['family'],expected_sn=key.sn)
store=ProjectStore(Path('/data/shinya.sqlite'))
service=ChildrenService(store,None,AppCatalog())
baseline=service.refresh_config(key,driver)
original=copy.deepcopy(next(u for u in baseline['users'] if u['uid']==UID))
state=service.state(key)
assert original['macs']==['1a:9c:c5:c5:b7:bb'] and not original['policies'] and not original['block'] and not original['pause']
s=requests.Session();s.trust_env=False
base='http://127.0.0.1:58543/api/v1/'
v=s.post(base+'base/homeUser/accountPasswordLogin',json={'account':ACCOUNT,'password':PASSWORD},timeout=8).json()
assert v['code']==0
s.headers['Authorization']='Bearer '+(v['data'].get('token') or v['data'].get('accessToken'))
done=[]
def post(name,values):
    v=s.post(base+'netguard/staGuard/'+name,json={'mainApSn':key.sn,'staId':UID,**values},timeout=25).json()
    assert v['code']==0,{'operation':name,'response':v}
    return v['data']
def runtime():
    return driver.execute(UFCommand('dev_sta','get','shinya_child',{'action':'runtime','uid':UID}))
try:
    now=datetime.now(ZoneInfo('Asia/Shanghai'))
    a=(now-timedelta(minutes=5)).strftime('%H:%M');b=(now+timedelta(minutes=5)).strftime('%H:%M')
    plan={'startTime':a,'endTime':b,'weekRepeat':'1,2,3,4,5,6,7','enableApps':'9-220-1-0'}
    post('addAppTimePlan',plan)
    cfg=service.refresh_config(key,driver);u=next(u for u in cfg['users'] if u['uid']==UID)
    pid=next(p['id'] for p in u['policies'] if p['type']=='1')
    live=runtime()
    assert pid in live['user']['policy'] and live['policies'],live
    assert all(m in live['sets']['child_guard_ip6_block'] for m in original['macs'])
    print(json.dumps({'stage':'native-app-rule','runtime':live}),flush=True)
    done.append('application allowlist and IPv6 native enforcement')
    post('editAppTimePlan',{**plan,'id':pid,'endTime':(now+timedelta(minutes=6)).strftime('%H:%M')})
    post('setAppTimePlanDisable',{'disable':1})
    assert not runtime()['user']['policy']
    post('setAppTimePlanDisable',{'disable':0})
    assert pid in runtime()['user']['policy']
    post('deleteAppTimePlan',{'id':pid})
    assert not runtime()['user']['policy']
    done.append('schedule add/edit/disable/enable/delete')
    post('setUsrOpt',{'usrOpt':1,'cutoffTimeSec':int(time.time())+30})
    assert all(m in runtime()['sets']['child_guard_skip'] for m in original['macs'])
    post('setUsrOpt',{'usrOpt':0})
    post('setUsrOpt',{'usrOpt':2,'cutoffTimeSec':int(time.time())-60})
    assert all(m in runtime()['sets']['child_guard_block'] for m in original['macs'])
    post('setUsrOpt',{'usrOpt':0})
    cutoff=int(time.time())+20
    post('setUsrOpt',{'usrOpt':2,'cutoffTimeSec':cutoff})
    assert not runtime()['sets']['child_guard_block']
    for n in range(35):
        time.sleep(1)
        if all(m in runtime()['sets']['child_guard_block'] for m in original['macs']):break
    else:raise AssertionError('delayed ban did not execute')
    post('setUsrOpt',{'usrOpt':0})
    done.append('temporary pass/immediate ban/delayed ban/restore')
    wrong=s.get(base+'netguard/staGuard/getAppTimePlans',params={'mainApSn':'G1TQ88T702111','staId':UID},timeout=8).json()
    assert wrong['code']==404,wrong
    done.append('foreign project UID rejected')
finally:
    try:
        driver.execute(UFCommand('dev_config','set','shinya_child_control',{'uid':UID,'cutoff':0}))
        service.write_user(key,original,driver)
    finally:
        service.save(key,STATE,state)
    after=service.refresh_config(key,driver)
    def normalized(c):
        return {u['uid']:{k:u.get(k) for k in ('uid','name','macs','policies','block','pause','delayedBlock')} for u in c['users']}
    assert normalized(after)==normalized(baseline),'native restoration mismatch'
    live=runtime()
    assert not live['user']['policy'] and not any(m in live['sets'].get(n,{}) for m in original['macs'] for n in live['sets'])
    print(json.dumps({'restored':True,'checks':done}),flush=True)
'''

def main():
    code=CODE.replace('ACCOUNT',repr(os.environ['SHINYA_ADMIN_ACCOUNT'])).replace('PASSWORD',repr(os.environ['SHINYA_ADMIN_PASSWORD']))
    code='try:\n'+''.join('    '+line+'\n' for line in code.splitlines())+'except Exception as error:\n    import traceback\n    print(traceback.format_exc(),flush=True)\n'
    c=connect_nas()
    try:
        out=run(c,'docker exec -i shinya-hub python -',stdin=code.encode(),sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=180)
        Path('test/_analysis/oct11-children/live-controls.jsonl').write_bytes(out)
        print(out.decode(),flush=True)
    finally:c.close()
if __name__=='__main__':main()
