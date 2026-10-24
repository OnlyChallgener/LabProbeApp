"""Bounded firmware diagnostics, isolated from telemetry and configuration writes."""
from concurrent.futures import ThreadPoolExecutor
import secrets
import threading
import time

from .commands import UFCommand

GET = UFCommand('dev_sta', 'get', 'dev_diag', None)
START = UFCommand('dev_sta', 'set', 'dev_diag', {'user': 'eweb', 'action': 'start'})
RESOURCE = 'hub.diagnosis.last'

TITLES = {
    'network_port_wiring': '网线连接', 'negotiating_speed': '端口协商速率',
    'check_get_dhcp_ip': 'WAN 地址获取', 'subnet_conflict': 'WAN/LAN 网段冲突',
    'loopback': '网络环路', 'mult_dhcp_server_conflict': '多 DHCP 服务器冲突',
    'ip_address_conflict': 'IP 地址冲突', 'route_config': '路由与外网连通性',
    'nexthop_connectivity': '上级网关连通性', 'dns_config': 'DNS 解析',
    'ip_session': '网络会话数量', 'dhcp_capacity': 'DHCP 地址容量',
    'flowctrl': '流量控制', 'cloud_service': '固件云服务配置',
}

# The catalog is the BE50 dev_diag test sequence, not the cloud APP's five
# analytics categories. Final cards contain only checks returned by firmware.
PLAN = [
    ('network_port_wiring', '0100', '网线连接'),
    ('negotiating_speed', '0200', '端口协商速率'),
    ('check_get_dhcp_ip', '0300', 'WAN 口配置'),
    ('check_get_dhcp_ip', '0500', 'WAN 地址获取'),
    ('subnet_conflict', '0600', 'WAN/LAN 网段冲突'),
    ('loopback', '0700', '网络环路'),
    ('mult_dhcp_server_conflict', '0800', '多 DHCP 服务器冲突'),
    ('ip_address_conflict', '0900', 'IP 地址冲突'),
    ('route_config', '1000', '路由与外网连通性'),
    ('nexthop_connectivity', '1100', '上级网关连通性'),
    ('dns_config', '1200', 'DNS 解析'),
    ('ip_session', '1300', '网络会话数量'),
    ('dhcp_capacity', '1400', 'DHCP 地址容量'),
    ('flowctrl', '1500', '流量控制'),
    ('cloud_service', '1600', '固件云服务配置'),
]


def group_title(kind, code):
    if kind == 'check_get_dhcp_ip' and str(code).startswith('03'):
        return 'WAN 口配置'
    return TITLES.get(kind, '网络状态检查')


def plan():
    return [{'type': k, 'code': c, 'title': t, 'status': 'pending', 'items': []}
            for k, c, t in PLAN]


def progress(value):
    try:
        return max(0, min(100, float(str(value.get('process', '0')).rstrip('%'))))
    except (TypeError, ValueError):
        return 0


def report(value, partial=False):
    """Adapt actual firmware checks to the existing Star result card contract."""
    items = []
    groups = []
    for group in value.get('list', []):
        if not isinstance(group, dict):
            continue
        group_items = []
        for row in group.get('list') or [group]:
            if not isinstance(row, dict):
                continue
            kind = group.get('type', '')
            code = str(row.get('code') or '')
            title = group_title(kind, code)
            if code == '0102': title = '外网口网线连接'
            if code == '0104': title = '局域网网线连接'
            if code == '1005': title = '外网连通性'
            data = row.get('data') if isinstance(row.get('data'), dict) else {}
            state = row.get('status', group.get('status'))
            outcome = '正常' if state == 'success' else '异常' if state == 'failed' else '检测中' if partial else '未完成'
            advice = ''
            if state == 'failed':
                advice = {
                    'negotiating_speed': '请检查网线、对端网口能力和速率配置。',
                    'network_port_wiring': '请检查对应接口的网线连接。',
                    'dns_config': '请检查 DNS 配置及域名解析。',
                    'nexthop_connectivity': '请检查上级网关的连接和配置。',
                    'subnet_conflict': '请检查 WAN 与 LAN 是否使用了相同网段。',
                    'mult_dhcp_server_conflict': '请检查局域网内是否有多个 DHCP 服务器。',
                    'ip_address_conflict': '请检查终端是否使用了重复的 IP 地址。',
                    'loopback': '请检查交换机或路由器接线是否形成环路。',
                }.get(kind, '请检查该项网络连接与配置。')
            item = {'code': code, 'type': kind, 'title': title, 'status': state,
                          'outcome': outcome, 'port': str(data.get('port') or ''),
                          'advice': advice, 'nativeResult': row.get('result', '')}
            items.append(item)
            group_items.append(item)
        if group_items:
            states = {i['status'] for i in group_items}
            state = 'failed' if 'failed' in states else 'success' if states == {'success'} else 'pending'
            groups.append({'type': group.get('type', ''), 'code': str(group.get('code') or ''),
                           'title': group_title(group.get('type', ''), group.get('code')),
                           'status': state, 'items': group_items})
    if not items:
        raise ValueError('固件尚未返回诊断结果')
    failed = [r for r in items if r['status'] == 'failed']
    unknown = [r for r in items if r['status'] not in {'success', 'failed'}]
    title = failed[0]['title'] + '异常' if failed else '网络检查完成'
    lines = []
    for item in items:
        label = item['title'] + (' · ' + item['port'] if item['port'] else '')
        lines.append(label + '：' + item['outcome'])
        if item['advice']: lines.append(item['advice'])
    return {'apProblemMain': {'problemMainType': 'firmware', 'problemMainReason': title,
                             'problemMainSolu': '\n'.join(lines)},
            'apProblemList': [], 'apSysInfo': {}, 'items': items, 'groups': groups,
            'errorCount': len(failed), 'unknownCount': len(unknown),
            'checkedCount': len(items), 'source': 'firmware.dev_diag',
            'firmwareStartedAt': value.get('start_time')}


class DiagnosticJobs:
    def __init__(self, registry, timeout=100, interval=2):
        self.registry = registry
        self.timeout = timeout
        self.interval = interval
        self._lock = threading.Lock()
        self._jobs = {}
        self._active = {}
        self._slots = threading.BoundedSemaphore(2)
        self._pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix='shinya-diagnosis')
        self._stop = threading.Event()

    def start(self, key):
        self.registry._driver(key)  # Verify ownership and configured transport before admission.
        with self._lock:
            self._jobs = {k: v for k, v in self._jobs.items() if time.monotonic() - v['created'] < 600}
            existing = self._active.get(key)
            if existing: return self._view(self._jobs[existing])
            if not self._slots.acquire(blocking=False):
                raise OverflowError('诊断任务较多，请稍后重试')
            token = secrets.token_urlsafe(24)
            job = {'key': key, 'jobId': token, 'state': 'queued', 'progress': 0,
                   'created': time.monotonic(), 'cancel': threading.Event(), 'plan': plan()}
            self._jobs[token] = job
            self._active[key] = token
        self._pool.submit(self._run, token)
        return self._view(job)

    def status(self, key, token):
        with self._lock:
            job = self._jobs.get(token)
            if job is None: raise LookupError('诊断结果已过期')
            if job['key'] != key: raise PermissionError('诊断不属于当前项目')
            return self._view(job)

    def cancel(self, key, token):
        # Stop this HUB observer. Do not invent a firmware stop command.
        with self._lock:
            job = self._jobs.get(token)
            if job is None: raise LookupError('诊断结果已过期')
            if job['key'] != key: raise PermissionError('诊断不属于当前项目')
            if job['state'] not in {'complete', 'failed', 'cancelled'}:
                job['cancel'].set()
                job['state'] = 'cancelled'
            return self._view(job)

    @staticmethod
    def _view(job):
        return {k: v for k, v in job.items() if k in {'jobId', 'state', 'progress', 'report', 'message', 'plan'}}

    def _update(self, token, **fields):
        with self._lock: self._jobs[token].update(fields)

    def _run(self, token):
        key = self._jobs[token]['key']
        cancelled = self._jobs[token]['cancel']
        try:
            if cancelled.is_set(): return
            # Only each short RPC holds the device lock. The diagnostic worker
            # never occupies the realtime collection pool while awaiting firmware.
            with self.registry.device_operation(key) as driver:
                before = driver.execute(GET)
                answer = driver.execute(START)
            if answer.get('rcode') not in (None, '00000000'):
                raise ValueError('路由器未接受诊断请求')
            self._update(token, state='running', progress=1)
            previous = before.get('start_time')
            began = time.monotonic()
            saw_running = False
            while time.monotonic() - began < self.timeout and not self._stop.is_set():
                if cancelled.wait(self.interval): return
                with self.registry.device_operation(key) as driver:
                    value = driver.execute(GET)
                percent = progress(value)
                saw_running = saw_running or percent < 100
                new_result = value.get('start_time') != previous or saw_running
                self._update(token, progress=min(99, percent))
                if new_result and value.get('list'):
                    self._update(token, report=report(value, partial=True))
                if new_result and percent >= 100 and value.get('rcode') == '00000000':
                    result = report(value)
                    self.registry.store.save(key.tenant, key.project, key.sn, RESOURCE, result)
                    self._update(token, state='complete', progress=100, report=result)
                    return
            raise TimeoutError('诊断超时，请稍后重新检测')
        except TimeoutError:
            self._update(token, state='failed', message='诊断超时，请稍后重新检测')
        except Exception:
            self._update(token, state='failed', message='未取得本次路由器诊断结果，请重新检测')
        finally:
            with self._lock:
                if self._active.get(key) == token: self._active.pop(key, None)
            self._slots.release()

    def close(self):
        self._stop.set()
        self._pool.shutdown(wait=True)
