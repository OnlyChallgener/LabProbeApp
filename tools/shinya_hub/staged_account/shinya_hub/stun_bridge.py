"""Project-bound adapter to the existing NAS-local STUN execution services."""
import threading
import re
import requests
from .local_service import binding_target, verify_device


class StunError(Exception):
    def __init__(self, message, code=409):
        super().__init__(message)
        self.code = code


class StunBridge:
    def __init__(self, bindings):
        self.bindings = {}
        self.locks = {}
        for row in bindings:
            identity = (str(row['tenant']), str(row['project']), str(row['sn']))
            port = int(row['port'])
            if not 1024 <= port <= 65535 or not row.get('token'):
                raise ValueError('Invalid local STUN binding')
            if identity in self.bindings:
                raise ValueError('Duplicate STUN binding')
            router_id = row.get('routerId', '')
            if router_id and (not isinstance(router_id, str) or not re.fullmatch(r'[a-z][a-z0-9_-]{1,31}', router_id)):
                raise ValueError('Invalid local STUN router ID')
            binding = (port, row['token'], '/r/' + router_id) if router_id else (port, row['token'])
            binding_target(binding)
            self.bindings[identity] = binding
            self.locks[identity] = threading.Lock()
        self.slots = threading.BoundedSemaphore(4)

    def request(self, key, operation, rule_id='', draft=None):
        identity = (key.tenant, key.project, key.sn)
        if identity not in self.bindings:
            raise LookupError('该路由器尚未接入 STUN 执行服务')
        if operation not in {'list', 'create', 'update', 'delete', 'start', 'stop', 'addresses'}:
            raise ValueError('STUN 操作无效')
        if operation not in {'list', 'create'} and not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', rule_id):
            raise ValueError('STUN 规则标识无效')
        if not self.slots.acquire(blocking=False):
            raise BlockingIOError('STUN 服务繁忙，请稍后重试')
        locked = False
        try:
            locked = self.locks[identity].acquire(blocking=False)
            if not locked:
                raise BlockingIOError('当前路由器正在处理 STUN 操作')
            base, token = binding_target(self.bindings[identity])
            session = requests.Session()
            session.trust_env = False
            session.headers['Authorization'] = 'Bearer ' + token
            before_ids = set()
            def checked(response):
                if len(response.content) > 1024 * 1024:
                    raise ValueError('STUN 返回数据过大')
                value = response.json()
                if not response.ok or value.get('ok') is not True:
                    raise StunError(str(value.get('error') or 'STUN 执行失败')[:300])
                return value
            def call(method, suffix='', body=None):
                try:
                    response = session.request(method, base + '/api/stun' + suffix,
                                           json=body, timeout=(2, 14), allow_redirects=False)
                except requests.Timeout as error:
                    if method == 'GET':
                        # Retry a read once within the App's existing request budget.
                        return checked(session.request('GET', base + '/api/stun' + suffix,
                            timeout=(2, 4), allow_redirects=False))
                    # A write may have committed after its response timed out.
                    # Never replay it: confirm only by the selected router's state.
                    try:
                        fresh = checked(session.get(base + '/api/stun', timeout=(2, 4), allow_redirects=False))
                        rows = fresh.get('rules')
                        if not isinstance(rows, list): raise ValueError('Invalid readback')
                        selected = next((r for r in rows if r.get('id') == rule_id), None)
                        if operation == 'delete' and selected is None:
                            return {'ok': True, 'confirmedAfterTimeout': True}
                        if operation in ('start', 'stop') and selected is not None and selected.get('enabled') is (operation == 'start'):
                            return {'ok': True, 'rule': selected, 'confirmedAfterTimeout': True}
                        if operation in ('create', 'update'):
                            def matches(row):
                                for name, value in (body or {}).items():
                                    actual = row.get(name)
                                    if isinstance(value, str):
                                        value = value.strip()
                                        if name in ('serviceType', 'transportProtocol', 'targetType'):
                                            actual, value = str(actual).lower(), value.lower()
                                    if actual != value: return False
                                return bool(body)
                            candidates = [r for r in rows if r.get('id') not in before_ids and matches(r)] if operation == 'create' else [selected] if selected is not None and matches(selected) else []
                            if len(candidates) == 1:
                                return {'ok': True, 'rule': candidates[0], 'confirmedAfterTimeout': True}
                    except (requests.RequestException, ValueError, StunError):
                        pass
                    raise StunError('操作结果暂未确认，请刷新核对，勿重复提交', 502) from error
                return checked(response)
            try:
                identity_reply = session.get(base + '/api/router/dashboard', timeout=(2, 8), allow_redirects=False)
                identity_reply.raise_for_status()
                verify_device(identity_reply.json(), key.sn)
                if operation == 'list':
                    return call('GET')
                if operation != 'list':
                    rows = call('GET').get('rules')
                    if not isinstance(rows, list): raise ValueError('STUN 规则列表无效')
                    before_ids = {r.get('id') for r in rows}
                    if operation != 'create' and rule_id not in before_ids:
                        raise LookupError('当前路由器中没有该 STUN 规则')
                if operation == 'addresses':
                    return call('GET', '/' + rule_id + '/addresses')
                if operation in {'update', 'delete', 'stop'}:
                    guard = session.get(base + '/api/wireguard/server',
                                        timeout=(2, 8), allow_redirects=False)
                    guard.raise_for_status()
                    document = guard.json()
                    if document.get('ok') is not True:
                        raise StunError('无法核实 WireGuard 绑定，请稍后重试')
                    profiles = (document.get('server') or {}).get('endpointProfiles', [])
                    if any(p.get('endpointSource') == 'stun' and p.get('stunRuleId') == rule_id for p in profiles):
                        raise StunError('该穿透规则正被 WireGuard 使用，请先解除绑定')
                if operation in {'create', 'update'}:
                    if not isinstance(draft, dict):
                        raise ValueError('STUN 规则内容无效')
                    # Only editable presentation/service fields cross this boundary.
                    fields = {'name', 'serviceType', 'transportProtocol', 'targetType',
                              'targetIpv4', 'targetPort', 'enabled'}
                    clean = {k: v for k, v in draft.items() if k in fields}
                    return call('POST' if operation == 'create' else 'PUT',
                                '' if operation == 'create' else '/' + rule_id, clean)
                if operation == 'delete':
                    return call('DELETE', '/' + rule_id)
                return call('POST', '/' + rule_id + '/' + operation, {})
            finally:
                session.close()
        except requests.RequestException as error:
            raise StunError('STUN 执行服务暂时无法连接，已保留页面数据', 503) from error
        finally:
            if locked:
                self.locks[identity].release()
            self.slots.release()
