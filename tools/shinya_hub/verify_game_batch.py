"""Live current-device acceleration checks; restore each router's prior game flag."""
import json,shlex,time,warnings
from pathlib import Path
from local_api import account,post,BASE
from remote_transport import connect_router,run
warnings.filterwarnings('ignore',message='Unverified HTTPS request')
out=Path('test/_analysis/oct12-game-batch');proof=[]

def native(c):
    return {module:json.loads(run(c,'dev_sta get -m '+module if module in ('rcgame','overview') else 'dev_config get -m '+module))
            for module in ('rcgame','flowctrl','flowctrl_qos')}
def rdpi(c):return run(c,"ps | sed -n '/rdpi/p' | sed '/sed /d'").decode().strip()
def main():
    with account() as s:
        for f,pid,sn in [('be72','900000001','G1TD8RX039025'),('be50','900000002','G1TQ88T702111')]:
            c=connect_router(f);body={'projectId':pid,'sn':sn};before=native(c);old_rdpi=rdpi(c)
            try:
                assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==sn
                other_sn='G1TQ88T702111' if f=='be72' else 'G1TD8RX039025'
                rejected=s.post(BASE+'oneStepGame/startFastAccelerate',json={**body,'sn':other_sn},timeout=15).json()
                assert rejected['code']==404,rejected
                result=post(s,'oneStepGame/startFastAccelerate',body)
                enabled=native(c);assert enabled['rcgame']['game']=='on' and enabled['rcgame']['enabled']=='true'
                assert enabled['rcgame']['device']==before['rcgame']['device'] and enabled['rcgame']['study']==before['rcgame']['study']
                value=post(s,'eSportAccelerate/getEsportAccelerateStatus',body);assert value['openStatus']==1
                telemetry=json.loads(run(c,'dev_sta get -m shinya_game '+shlex.quote(json.dumps({'action':'telemetry'}))))
                assert telemetry['source']=='firmware.rcgame' and isinstance(telemetry['probes'],(dict,list))
                reports=post(s,'eSportAccelerate/getGameAccelerateHistoryNew',body);assert isinstance(reports,list)
                statistics=post(s,'game/accelerate/getGameStatistics',body);assert statistics['source']=='hub-firmware-rounds'
                post(s,'eSportAccelerate/closeESportAccelerate',body)
                disabled=native(c);assert disabled['rcgame']['game']=='off'
                assert post(s,'eSportAccelerate/getEsportAccelerateStatus',body)['openStatus']==0
                assert old_rdpi==rdpi(c),'Feature recognition process changed'
                row={'family':f,'sn':sn,'enabledReadback':enabled['rcgame']['game'],'disabledReadback':disabled['rcgame']['game'],
                     'foreignSNCode':rejected['code'],'featureProcessUnchanged':True,'reportCount':len(reports),
                     'telemetryProbeCount':len(telemetry['probes'])}
                proof.append(row);print(json.dumps(row),flush=True)
            finally:
                post(s,'eSportAccelerate/openESportAccelerate' if before['rcgame']['game']=='on' else 'eSportAccelerate/closeESportAccelerate',body)
                after=native(c)
                # Native rcgame can enable its existing QoS game rule. Restore
                # only that rule's enable bit if the test changed it; leave all
                # bandwidths, other rules and the feature library intact.
                for b in before['flowctrl_qos'].get('list',[]):
                    a=next((r for r in after['flowctrl_qos'].get('list',[]) if r.get('comment')==b.get('comment')),None)
                    if b.get('comment')=='games' and a and a.get('enable')!=b.get('enable'):
                        payload={'list':[{'comment':'games','enable':b['enable'],'tc_pri':b['tc_pri']}]}
                        run(c,'dev_config update -m flowctrl_qos '+shlex.quote(json.dumps(payload)))
                after=native(c)
                assert after['rcgame']['game']==before['rcgame']['game']
                assert after['rcgame']['device']==before['rcgame']['device'] and after['rcgame']['study']==before['rcgame']['study']
                for field in ('tcSwitch','reserve_switch','list'):
                    a=after['flowctrl'].get(field);b=before['flowctrl'].get(field)
                    if field=='list':
                        def canonical(rows,flow):return [{**r,'enable':r.get('enable',flow.get('tcSwitch','off'))} for r in rows]
                        a=canonical(a,after['flowctrl']);b=canonical(b,before['flowctrl'])
                    assert a==b,(f,field)
                assert old_rdpi==rdpi(c)
                c.close()
    (out/'live-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf8')
if __name__=='__main__':main()
