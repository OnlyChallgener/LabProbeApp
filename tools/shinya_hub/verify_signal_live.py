"""Exercise wireless save/readback on both owned routers; restore every temporary setting."""
import copy,json,shlex,time,warnings
from pathlib import Path
from datetime import datetime,timedelta
from zoneinfo import ZoneInfo
from local_api import account,BASE
from remote_transport import connect_router,run
warnings.filterwarnings('ignore',message='Unverified HTTPS request')
OUT=Path('test/_analysis/oct12-signal-tools/live-writes.json')
def unwrap(v):
    for _ in range(6):
        if isinstance(v,dict) and 'rcode' in v and 'data' in v:v=v['data']
        elif isinstance(v,str):v=json.loads(v)
        else:break
    return v
def call(s,path,body,method='POST'):
    r=s.request(method,BASE+path,json=body if method=='POST' else None,params=body if method=='GET' else None,timeout=75)
    v=r.json()
    if v.get('code')!=0:raise RuntimeError(path+': '+str(v.get('msg')))
    return v['data']
def stripped(v):return {k:x for k,x in v.items() if k not in {'configId','configTime','currentTime','subConfigId','version'}}
def radio_semantics(v):
    v=copy.deepcopy(stripped(v))
    for row in v.get('radioList',[]):
        row.pop('beacon_txpow',None) # Firmware computes the current beacon power.
        if row.get('txpower')=='auto':row['txpower']='100'
    return v
proof=[]
with account() as session:
    for family,pid,sn in [('be72','900000001','G1TD8RX039025'),('be50','900000002','G1TQ88T702111')]:
        c=connect_router(family);saved={};checks=[]
        def native(module,action='get',body=None,namespace='ac_config'):
            command=namespace+' '+action+' -m '+module
            if body is not None:command+=' '+shlex.quote(json.dumps(body,ensure_ascii=False,separators=(',',':')))
            text=run(c,command,timeout=45).strip()
            return unwrap(json.loads(text)) if text else {}
        try:
            saved['wireless']=native('wireless');saved['radio']=native('radio',namespace='dev_config')
            base={'projectId':pid,'sn':sn}
            day=(datetime.now(ZoneInfo('Asia/Shanghai'))+timedelta(days=1)).isoweekday()
            policy=[{'weekDays':[day],'beginEndTimes':[{'beginMinute':180,'endMinute':185}]}]
            call(session,'device/manage/wireless/heathy/cfg',{**base,'enable':True,'timePolicies':policy})
            assert call(session,'device/manage/wireless/healthy/cfg',base,'GET')['timePolicies']==policy
            checks.append('eco future schedule saved and read back')
            call(session,'device/manage/wireless/heathy/cfg',{**base,'enable':False,'timePolicies':[]})
            wlan=next(v['wlanId'] for v in saved['wireless']['ssidList'] if v['enable']=='true')
            call(session,'device/manage/wifi/timeManage',{**base,'wlanId':int(wlan),'enable':True,'timePolicies':policy})
            assert call(session,'device/manage/wireless/timeManage/detail',{**base,'wlanId':int(wlan)},'GET')['timePolicies']==policy
            checks.append('Wi-Fi OFF interval saved as native ON complement and read back')
            call(session,'device/manage/wifi/timeManage',{**base,'wlanId':int(wlan),'enable':False,'timePolicies':[]})
            rows=saved['radio']['radioList']
            for power in ('50','75','100'):
                command='dev_config set -m radio '+shlex.quote(json.dumps({'radioList':[{**r,'txpower':power} for r in rows]},separators=(',',':')))
                call(session,'api/device/command/sendCmd',{**base,'apSn':sn,'cmd':command,'type':1})
                actual=native('radio',namespace='dev_config')
                assert all(str(r['txpower'])==power for r in actual['radioList'])
                assert [r['channel'] for r in actual['radioList']]==[r['channel'] for r in rows]
            checks.append('50/75/100 power writes verified, channels preserved')
        except Exception as error:
            print(family,'test failure',type(error).__name__,str(error)[:200],flush=True)
            raise
        finally:
            try:
                if saved.get('wireless'):
                    actual=native('wireless')
                    if stripped(actual)!=stripped(saved['wireless']):native('wireless','set',stripped(saved['wireless']))
                    assert stripped(native('wireless'))==stripped(saved['wireless'])
                if saved.get('radio'):
                    actual=native('radio',namespace='dev_config')
                    if stripped(actual)!=stripped(saved['radio']):native('radio','set',stripped(saved['radio']),namespace='dev_config')
                    assert radio_semantics(native('radio',namespace='dev_config'))==radio_semantics(saved['radio'])
                checks.append('all baseline wireless and radio settings restored')
                print(family,checks,flush=True)
                proof.append({'family':family,'project':pid,'sn':sn,'checks':checks})
                OUT.write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
            finally:c.close()
