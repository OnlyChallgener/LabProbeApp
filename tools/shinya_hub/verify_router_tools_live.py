"""Authorized temporary test rules; preserve existing project configuration."""
import json,time
from pathlib import Path
from local_api import account,post
OUT=Path('test/_analysis/oct12-router-tools/live-proof.json')
def rows(value,field='list'):return (value.get('data') or value).get(field,[])
def main():
    proof=[]
    with account() as session:
        projects=post(session,'apMonitor/getProjectList2',{})['dataList']
        for project in projects:
            device=project['apList'][0];sn=device['sn'];pid=project['projectId']
            scope={'projectId':pid,'sn':sn}
            def call(section,action='read',id='',draft=None):
                return post(session,'hub/routerTools',{**scope,'section':section,'action':action,'recordId':id,'draft':draft})
            caps=call('capabilities')['features'];result={'sn':sn,'capabilities':caps}
            # The other project's records are never included in these snapshots.
            before=call('ddns')['records'];ids={r['id'] for r in before};created=''
            try:
                value=call('ddns','create',draft={'provider':'cloudflare','hostname':'codex-check.invalid',
                    'recordTypes':['A'],'ttl':600,'enabled':False,'credentials':{}})
                created=next(r['id'] for r in value['records'] if r['id'] not in ids)
                call('ddns','update',created,{'ttl':1200,'enabled':False})
                row=next(r for r in call('ddns')['records'] if r['id']==created)
                assert row['ttl']==1200 and row['enabled'] is False
                result['customDdnsCrud']=True
            finally:
                if created:call('ddns','delete',created)
            assert {r['id'] for r in call('ddns')['records']}==ids
            cert_id=''
            try:
                v=call('certificates','create',draft={'note':'temporary QA','domain':'codex-check.invalid','expiresOn':'2026-10-13'})
                cert_id=next(r['id'] for r in v['records'] if r['domain']=='codex-check.invalid')
                v=call('certificates','update',cert_id,{'expiresOn':'2026-10-11'})
                assert next(r for r in v['records'] if r['id']==cert_id)['status']=='expired'
                result['certificateCrud']=True
            finally:
                if cert_id:call('certificates','delete',cert_id)
            if caps.get('ddns'):
                baseline=call('nativeDdns');ids={r['service'] for r in rows(baseline)};native_id=''
                try:
                    draft={'service_name':'aliyun.com','domain':'codex-check.invalid','username':'disabled-test',
                        'password':'disabled-test-value','interface':'wan','use_ipv6':'0','enabled':'0'}
                    v=call('nativeDdns','create',draft=draft)
                    native_id=next(r['service'] for r in rows(v) if r['service'] not in ids)
                    call('nativeDdns','update',native_id,{**{k:v for k,v in draft.items() if k!='password'},'domain':'codex-check2.invalid'})
                    assert all('password' not in r for r in rows(call('nativeDdns')))
                    result['nativeDdnsCrud']=True
                finally:
                    if native_id:call('nativeDdns','delete',native_id)
                assert {r['service'] for r in rows(call('nativeDdns'))}==ids
            if caps.get('firewall'):
                baseline=call('firewall')['data'];original=baseline['order']['forward_ipv4'];created=[]
                ids={r['uuid'] for r in baseline['list']}
                try:
                    for n in range(2):
                        draft={'ruleName':'codex-disabled-'+str(n),'direction':'forward','ipVersion':'ipv4',
                            'proto':'tcp','srcIP':'192.0.2.253','destIP':'192.0.2.254','srcPort':'',
                            'destPort':'57341','target':'ACCEPT','enable':'0','ipv6SuffixSrc':'',
                            'ipv6SuffixDest':'','inIface':'wan','outIface':'lan'}
                        v=call('firewall','create',draft=draft)['data']
                        row=next(r for r in v['list'] if r['uuid'] not in ids)
                        created.append(row['uuid']);ids.add(row['uuid'])
                    v=call('firewall')['data'];current=v['order']['forward_ipv4']
                    changed=[x for x in current if x not in created]+list(reversed(created))
                    call('firewall','reorder',draft={'scope':'forward_ipv4','uuids':changed})
                    call('firewall','update',created[0],{'destPort':'57342','ruleName':'codex-verified'})
                    # Enabled rule matches only documentation-only addresses.
                    call('firewall','enable',created[0],{'enabled':True})
                    call('firewall','enable',created[0],{'enabled':False})
                    result['firewallCrudAndPriority']=True
                finally:
                    for id in created:call('firewall','delete',id)
                fresh=call('firewall')['data']
                assert fresh['order']['forward_ipv4']==original
                assert {r['uuid'] for r in fresh['list']}=={r['uuid'] for r in baseline['list']}
            signal=post(session,'homeAp/getStaList2',scope)
            data=signal if isinstance(signal,list) else signal.get('sta_list') or signal.get('list') or []
            result['signalContractKeys']=sorted(set().union(*(set(r) for r in data))) if data else []
            proof.append(result);print(sn,'temporary settings verified and cleaned',flush=True)
    OUT.write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8')
if __name__=='__main__':main()
