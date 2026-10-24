"""Explicit NAS-local execution routes. Never guess a router or fall back to root."""
import re


def binding_target(binding):
    if len(binding) not in (2, 3):
        raise ValueError('执行服务绑定格式无效')
    port, token = binding[:2]
    prefix = binding[2] if len(binding) == 3 else ''
    if type(port) is not int or not 1024 <= port <= 65535 or not isinstance(token, str) or not token:
        raise ValueError('执行服务绑定无效')
    if not isinstance(prefix, str) or (prefix and not re.fullmatch(r'/r/[a-z][a-z0-9_-]{1,31}', prefix)):
        raise ValueError('执行服务的路由路径无效')
    return f'http://127.0.0.1:{port}{prefix}', token


def verify_device(document, expected_sn):
    details = (document.get('data') or document).get('details') or {}
    reported = [(details.get(part) or {}).get('serialNumber') for part in ('ap', 'identity')]
    if not any(reported) or any(sn and sn != expected_sn for sn in reported):
        raise PermissionError('执行服务中的路由器与当前项目不一致')
