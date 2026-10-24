"""Selected-router access to the existing LabProbe DDNS/firewall engines."""
import copy
import re
import threading
import time
import uuid
from datetime import date,datetime
from urllib.parse import quote
import requests
from .local_service import binding_target, verify_device

FIREWALL_FIELDS={'ruleName','direction','ipVersion','proto','srcIP','destIP','srcPort','destPort',
                 'target','enable','ipv6SuffixSrc','ipv6SuffixDest','inIface','outIface'}
NATIVE_FIELDS={'service_name','domain','username','password','interface','use_ipv6','enabled'}
DDNS_FIELDS={'name','provider','hostname','recordTypes','recordValues','enabled','ttl','credentials'}

class RouterToolsBridge:
    def __init__(self,store,bindings):
        self.store=store
        self.bindings=dict(bindings)
        self.locks={key:threading.RLock() for key in self.bindings}
        self.slots=threading.BoundedSemaphore(4)

    def request(self,key,section,action='read',record_id='',draft=None):
        identity=(key.tenant,key.project,key.sn)
        if not self.store.owned_device(*identity):raise PermissionError('路由器不属于当前项目')
        if section=='certificates':return self.certificates(key,action,record_id,draft)
        if identity not in self.bindings:raise LookupError('该路由器尚未接入设置服务')
        if section not in ('firewall','nativeDdns','ddns','providers','capabilities'):
            raise ValueError('设置类别无效')
        if action not in ('read','create','update','delete','enable','reorder','publish'):
            raise ValueError('设置操作无效')
        if record_id and not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',record_id):
            raise ValueError('记录标识无效')
        if draft is not None and not isinstance(draft,dict):raise ValueError('配置内容无效')
        if not self.slots.acquire(blocking=False):raise BlockingIOError('设置服务繁忙，请稍后重试')
        try:
            with self.locks[identity]:
                base,token=binding_target(self.bindings[identity])
                s=requests.Session();s.trust_env=False
                s.headers['Authorization']='Bearer '+token
                def call(method,path,body=None):
                    response=s.request(method,base+path,json=body,
                        timeout=(2,14),allow_redirects=False)
                    if len(response.content)>1024*1024:raise ValueError('设置返回数据过大')
                    value=response.json()
                    accepted=(value.get('ok') is True or isinstance(value.get('data'),dict)
                              or (path=='/api/router/capabilities' and isinstance(value.get('features'),dict)))
                    if not response.ok or not accepted or value.get('error'):
                        raise ValueError(str(value.get('message') or value.get('error') or '路由器配置失败')[:240])
                    if path.startswith('/api/router/ddns'):
                        data=value.get('data') or {}
                        for field in ('list','services','data'):
                            for row in data.get(field,[]) if isinstance(data.get(field),list) else []:
                                if isinstance(row,dict):row.pop('password',None)
                    return {**value,'ok':True}
                try:
                    dashboard=call('GET','/api/router/dashboard')
                    verify_device(dashboard,key.sn)
                    if section in ('providers','capabilities'):
                        if action!='read':raise ValueError('请求方式无效')
                        return call('GET','/api/ddns/providers' if section=='providers' else '/api/router/capabilities')
                    if action!='read' and section in ('firewall','nativeDdns'):
                        features=call('GET','/api/router/capabilities').get('features',{})
                        if features.get('firewall' if section=='firewall' else 'ddns') is not True:
                            raise ValueError('当前固件不支持这套'+('防火墙规则' if section=='firewall' else '原生 DDNS')+'设置')
                    path={'firewall':'/api/router/firewall','nativeDdns':'/api/router/ddns','ddns':'/api/ddns'}[section]
                    current=call('GET',path+'?force=1')
                    if action=='read':return current
                    data=current.get('data') or current
                    rows=(data.get('list') if section=='firewall' else data.get('records') if section=='ddns'
                          else data.get('data') or data.get('list') or data.get('services')) or []
                    id_field={'firewall':'uuid','nativeDdns':'service','ddns':'id'}[section]
                    def row_id(row):return str(row.get(id_field) or row.get('serviceId') or '')
                    existing=next((r for r in rows if row_id(r)==record_id),None)
                    if action not in ('create','reorder') and existing is None:
                        raise LookupError('当前路由器中没有该记录')
                    if section=='firewall':
                        if action=='reorder':
                            scope=str((draft or {}).get('scope',''))
                            if not re.fullmatch(r'(inbound|outbound|forward)_ipv[46]',scope):
                                raise ValueError('排序范围无效')
                            direction,version=scope.split('_')
                            expected={row_id(r) for r in rows if r.get('direction')==direction
                                      and r.get('ipVersion') in (version,'dual')}
                            ids=(draft or {}).get('uuids')
                            if not isinstance(ids,list) or len(ids)!=len(set(ids)) or set(ids)!=expected:
                                raise ValueError('规则已变化，请刷新后重新排序')
                            call('POST',path+'/reorder',{'scope':scope,'uuids':ids})
                            fresh=call('GET',path+'?force=1')
                            if (fresh.get('data') or {}).get('order',{}).get(scope)!=ids:
                                raise RuntimeError('路由器规则优先级回读不一致')
                            return fresh
                        suffix='/rules'+('/'+quote(record_id,safe='') if record_id else '')
                        if action=='enable':
                            if type((draft or {}).get('enabled')) is not bool:raise ValueError('启停状态无效')
                            call('PATCH',path+suffix+'/enabled',{'enabled':draft['enabled']})
                        elif action=='delete':call('DELETE',path+suffix)
                        elif action in ('create','update'):
                            body={k:v for k,v in (draft or {}).items() if k in FIREWALL_FIELDS}
                            if action=='update':body={**{k:v for k,v in existing.items() if k in FIREWALL_FIELDS},**body}
                            call('POST' if action=='create' else 'PUT',path+suffix,body)
                        else:raise ValueError('防火墙操作无效')
                    elif section=='nativeDdns':
                        suffix='/'+quote(record_id,safe='') if record_id else ''
                        if action=='delete':call('DELETE',path+suffix)
                        elif action in ('create','update'):
                            body={k:v for k,v in (draft or {}).items() if k in NATIVE_FIELDS}
                            call('POST' if action=='create' else 'PUT',path+suffix,body)
                        else:raise ValueError('原生 DDNS 操作无效')
                    else:
                        suffix='/'+quote(record_id,safe='') if record_id else ''
                        if action=='delete':call('DELETE',path+suffix)
                        elif action=='publish':
                            # A provider error is a valid result, never a fabricated success.
                            return call('POST',path+suffix+'/update',{'force':True})
                        elif action in ('create','update','enable'):
                            body={k:v for k,v in (draft or {}).items() if k in DDNS_FIELDS}
                            if existing is not None:
                                body={**{k:v for k,v in existing.items() if k in DDNS_FIELDS},**body}
                            call('POST' if action=='create' else 'PUT',path+suffix,body)
                        else:raise ValueError('DDNS 操作无效')
                    fresh=call('GET',path+'?force=1')
                    if section=='firewall':
                        after=(fresh.get('data') or {}).get('list',[])
                        if action=='delete':verified=not any(row_id(r)==record_id for r in after)
                        else:
                            target=next((r for r in after if row_id(r)==record_id),None) if record_id else next(
                                (r for r in after if row_id(r) not in {row_id(v) for v in rows}
                                 and str(r.get('ruleName'))==str((draft or {}).get('ruleName'))),None)
                            expected=({'enable':'1' if draft['enabled'] else '0'} if action=='enable' else body)
                            verified=bool(target and all(str(target.get(k,''))==str(v) for k,v in expected.items()))
                        if not verified:raise RuntimeError('防火墙规则回读不一致，请刷新核对')
                    elif section=='nativeDdns':
                        after=(fresh.get('data') or {}).get('list',[])
                        if action=='delete':verified=not any(row_id(r)==record_id for r in after)
                        else:
                            target=next((r for r in after if row_id(r)==record_id),None) if record_id else next(
                                (r for r in after if row_id(r) not in {row_id(v) for v in rows}
                                 and r.get('domain')==body.get('domain') and r.get('service_name')==body.get('service_name')),None)
                            verified=bool(target and all(str(target.get(k,''))==str(v) for k,v in body.items() if k!='password'))
                            if body.get('password'):verified=verified and bool(target.get('passwordConfigured'))
                        if not verified:raise RuntimeError('原生 DDNS 配置回读不一致')
                    return fresh
                finally:s.close()
        except requests.RequestException as error:
            raise ConnectionError('路由器设置服务暂不可用') from error
        finally:self.slots.release()

    def certificates(self,key,action,record_id,draft):
        resource='hub.router.certificates.v1'
        with self.store.atomic():
            saved=self.store.cached(key.tenant,key.project,key.sn,resource)
            rows=copy.deepcopy((saved or {}).get('value',{}).get('records',[]))
            if action!='read':
                old=next((r for r in rows if r['id']==record_id),None)
                if action!='create' and old is None:raise LookupError('当前项目中没有该证书记录')
                if action=='delete':rows=[r for r in rows if r['id']!=record_id]
                elif action in ('create','update'):
                    value={k:str(v).strip() for k,v in (draft or {}).items()
                           if k in ('note','domain','organization','provider','appliedOn','expiresOn','url')}
                    value={**(old or {}),**value}
                    if not value.get('domain'):raise ValueError('请填写证书域名')
                    for field in ('appliedOn','expiresOn'):
                        if value.get(field):date.fromisoformat(value[field])
                    if not value.get('expiresOn'):raise ValueError('请填写到期日期')
                    value['id']=record_id or uuid.uuid4().hex
                    rows=[r for r in rows if r['id']!=record_id]+[value]
                else:raise ValueError('证书操作无效')
                self.store.save(key.tenant,key.project,key.sn,resource,{'records':rows},observed=time.time())
        from .daily import CHINA
        today=datetime.now(CHINA).date()
        result=[]
        for row in sorted(rows,key=lambda r:r['expiresOn']):
            remaining=(date.fromisoformat(row['expiresOn'])-today).days
            result.append({**row,'remainingDays':remaining,
                           'status':'expired' if remaining<0 else 'due' if remaining<=7 else 'valid'})
        return {'ok':True,'records':result}
