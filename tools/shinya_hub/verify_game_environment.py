"""Exercise both project-owned game checks without changing router settings."""
import json,time,warnings
from pathlib import Path
from local_api import account,BASE
warnings.filterwarnings('ignore',message='Unverified HTTPS request')
OUT=Path('test/_analysis/oct12-game-environment')
def call(s,leaf,body):
    value=s.post(BASE+'game/'+leaf,json=body,timeout=35).json()
    assert value['code']==0,(leaf,value.get('code'),value.get('msg'))
    return value['data']
def main():
    rows=[]
    with account() as s:
        for family,pid,sn in [('be72','900000001','G1TD8RX039025'),('be50','900000002','G1TQ88T702111')]:
            base={'projectId':pid,'sn':sn};start=time.monotonic()
            job=call(s,'gameEnvCheck/startCheck',base)
            first=call(s,'gameEnvCheck/getCheckDetail',{**base,'checkId':job})
            assert set(first['detailMap'])=={'sta','wifi','ap','net','game'}
            while time.monotonic()-start<100:
                value=call(s,'gameEnvCheck/getCheckDetail',{**base,'checkId':job})
                if value.get('allCheckStatus'):break
                time.sleep(1)
            assert value.get('allCheckStatus'), 'Check never completed'
            assert all(r.get('source')=='current-router-firmware' for r in value['detailMap'].values()),value['detailMap']
            assert call(s,'gameEnvCheck/getLastCheckDetail',base)['id']==job
            card=call(s,'accelerate/getCurrentGameEnv',base)
            assert all(isinstance(card[n],(int,float)) and 0<=card[n]<=100 for n in ('botherIndex','seizeIndex','weakSignalIndex'))
            other={'projectId':'900000002' if family=='be72' else '900000001','sn':'G1TQ88T702111' if family=='be72' else 'G1TD8RX039025','checkId':job}
            forbidden=s.post(BASE+'game/gameEnvCheck/getCheckDetail',json=other,timeout=10).json()
            assert forbidden['code']==403
            (OUT/(family+'-live-result.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
            row={'family':family,'project':pid,'seconds':round(time.monotonic()-start,2),'statuses':{k:r['checkStatus'] for k,r in value['detailMap'].items()},'card':card,'otherProjectPollCode':forbidden['code']}
            rows.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
    (OUT/'live-proof.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf8')
if __name__=='__main__':main()
