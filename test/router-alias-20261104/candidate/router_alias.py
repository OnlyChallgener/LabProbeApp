"""Persistent App router display aliases, separate from firmware hostName."""
import re
from .identifiers import mac_key

RESOURCE = 'hub/router-display-alias'


def current(store, key):
    row = store.cached(key.tenant, key.project, key.sn, RESOURCE)
    return row['value'].get('alias') if row and isinstance(row['value'], dict) else None


def rename(store, key, alias, expected_mac):
    if not isinstance(alias, str):
        raise ValueError('名称格式无效')
    alias = alias.strip()
    if not alias or len(alias.encode('utf-8')) > 64 or any(ord(c) < 32 or ord(c) == 127 for c in alias):
        raise ValueError('名称须为 1–64 字节且不能含控制字符')
    mac = mac_key(expected_mac)
    if not re.fullmatch(r'(?:[0-9a-f]{2}:){5}[0-9a-f]{2}', mac):
        raise ValueError('缺少有效路由器 MAC')
    with store.atomic():
        with store.connect() as db:
            row = db.execute('SELECT metadata FROM devices WHERE tenant=? AND project=? AND sn=?',
                             (key.tenant, key.project, key.sn)).fetchone()
            if not row or store.device_removed(key.tenant, key.project, key.sn):
                raise LookupError('设备已解绑')
        # The key was selected by exact project + projected router MAC in the route.
        # Keep this independent of startup metadata imports and native hostname refreshes.
        store.save(key.tenant, key.project, key.sn, RESOURCE, {'alias': alias, 'apMac': mac})
        if current(store, key) != alias:
            raise RuntimeError('名称保存核验失败')
    return alias
