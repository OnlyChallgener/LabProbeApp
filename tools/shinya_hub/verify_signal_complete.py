import copy,json,shlex,time,warnings
from pathlib import Path
from urllib.parse import urlsplit
from local_api import account,BASE
from remote_transport import connect_router,run
warnings.filterwarnings('ignore',message='Unverified HTTPS request')

def unwrap(v):
    for _ in range(6):
        if isinstance(v,dict) and 'rcode' in v and 'data' in v:v=v['data']
        elif isinstance(v,str):v=json.loads(v)
        else:break
    return v
def clean(v):return {k:x for k,x in v.items() if k not in {'configId','configTime','currentTime','subConfigId','version'}}
def same(v):
    v=copy.deepcopy(clean(v))
    for row in v.get('radioList',[]):
        row.pop('beacon_txpow',None)
        if row.get('txpower')=='auto':row['txpower']='100'
    return v

def main():
    proof=[]
    with account() as s:
        for family,pid,sn in [('be72','900000001','G1TD8RX039025'),('be50','900000002','G1TQ88T702111')]:
            base={'projectId':pid,'sn':sn};row={'family':family,'project':pid}
            value=s.get(BASE+'netquality/signal/simulation/getSignalSimulationResult',params=base,timeout=90).json()
            assert value['code']==0,{'code':value['code'],'msg':value.get('msg')}
            result=value.get('data') or {};row['floorResult']=bool(result.get('imageCroppedUrl'));row['floorArea']=result.get('area')
            value=s.get(BASE+'netquality/signal/simulation/getImageUploadSignature',params=base,timeout=90).json()
            assert value['code']==0,{'code':value['code'],'msg':value.get('msg')}
            signature=value['data'];row['floorUploadHost']=urlsplit(signature['host']).hostname;row['floorUploadSignatureFields']=sorted(signature)
            c=connect_router(family);saved=None
            def native(action='get',data=None):
                text=run(c,'dev_config '+action+' -m radio'+(' '+shlex.quote(json.dumps(data,separators=(',',':'))) if data else ''),timeout=45).strip()
                return unwrap(json.loads(text)) if text else {}
            try:
                identity=unwrap(json.loads(run(c,'dev_sta get -m sysinfo')));assert identity['serial_num']==sn
                saved=native();other=[r for r in saved['radioList'] if r['type']!='2.4G']
                t=time.monotonic();value=s.post(BASE+'device/channel/channelOptimize',json={**base,'bandStr':'2.4G'},timeout=100).json()
                assert value['code']==0,{'code':value['code'],'msg':value.get('msg')}
                result=value['data'];actual=native()
                assert [r for r in actual['radioList'] if r['type']!='2.4G']==other
                status=unwrap(json.loads(run(c,'dev_sta get -m radio_channel')))
                current=dict(zip(status['band'].split(','),status['cur_channel'].split(',')))
                assert current['2.4G']==result['afterChannel']
                row['optimizeSeconds']=round(time.monotonic()-t,2);row['optimization']=result
            finally:
                try:
                    if saved is not None:
                        native('set',clean(saved));assert same(native())==same(saved)
                        row['radioConfigurationRestored']=True
                finally:c.close()
            proof.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
            Path('test/_analysis/oct12-signal-tools/complete/live-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')

if __name__=='__main__':
    main()
