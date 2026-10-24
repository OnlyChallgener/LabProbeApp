"""Read current scoped schedules; optionally clear only the known BE50 UI test."""
import json
import sys
import warnings
from pathlib import Path
from local_api import account, BASE
from signal_power_snapshot import read_powers

warnings.filterwarnings('ignore', message='Unverified HTTPS request')

def get(s, path, base):
    value=s.get(BASE+path, params=base, timeout=45).json()
    assert value['code']==0, (path,value.get('code'),value.get('msg'))
    return value['data']

def main():
    with account() as s:
        if '--clear-ui-test' in sys.argv:
            base={'projectId':'900000002','sn':'G1TQ88T702111','wlanId':1}
            before=get(s,'device/manage/wireless/timeManage/detail',base)
            expected=[{'weekDays':[2],'beginEndTimes':[{'beginMinute':0,'endMinute':360}]}]
            assert before['timePolicies']==expected, 'Unexpected schedule: preserve user changes'
            value=s.post(BASE+'device/manage/wifi/timeManage',json={**base,'enable':False,'timePolicies':[]},timeout=45).json()
            assert value['code']==0
        result={}
        for family,pid,sn in [('be72','900000001','G1TD8RX039025'),('be50','900000002','G1TQ88T702111')]:
            base={'projectId':pid,'sn':sn}
            wifi=get(s,'device/manage/wireless/timeManage/list',base)
            result[family]={'eco':get(s,'device/manage/wireless/healthy/cfg',base),
                            'cron':get(s,'device/channel/getChannelOptimizeCronJob',base),
                            'wifi':[{k:r[k] for k in ('sn','wlanId','enable','timePolicies')} for r in wifi['rows']]}
        result['power']=read_powers()
    if '--final' in sys.argv or '--clear-ui-test' in sys.argv:
        for family in ('be72','be50'):
            assert not result[family]['eco']['enable'] and not result[family]['eco']['timePolicies']
            assert not result[family]['cron']['isSetting'] and not result[family]['cron']['timerTimePolicy']
            assert all(not r['enable'] and not r['timePolicies'] for r in result[family]['wifi'])
        label='final-state'
    else:label='wifi-ui-test-state'
    Path('test/_analysis/oct12-signal-tools/'+label+'.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()
