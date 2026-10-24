"""Apply a narrowly scoped compatibility adapter to the standalone HUB source."""
from pathlib import Path

root = Path('D:/Github/ShinyaHub')
here = Path(__file__).resolve().parent
(root/'shinya_hub/beta.py').write_bytes((here/'beta_adapter.py').read_bytes())
(root/'tests/test_beta_routes.py').write_bytes((here/'test_beta_routes.py').read_bytes())
api = root/'shinya_hub/api.py'
source = api.read_text(encoding='utf-8')
if 'from .beta import' not in source:
    source = source.replace('from .stun_bridge import StunError', 'from .stun_bridge import StunError\nfrom .beta import BetaUnavailable, upgrade_row')
marker = "            if resource == 'hub/beta':"
if "resource == 'homeAp/isSoftVersionUpdateList'" not in source:
    assert marker in source
    source = source.replace(marker, '''            if resource == 'homeAp/getBetaStatus':
                selected_project(principal, raw)
                # Only the detection source changes; no router beta flag is written.
                return reply(1)
            if resource == 'homeAp/isSoftVersionUpdateList':
                project = selected_project(principal, raw)
                rows = raw.get('dataList')
                if not isinstance(rows, list) or len(rows) > 128:
                    raise ValueError('Invalid router detection list')
                keys = []
                for row in rows:
                    sn = row.get('sn') if isinstance(row, dict) else None
                    if not sn or not store.owned_device(principal.tenant, project['projectId'], str(sn)):
                        raise LookupError('Device does not belong to selected project')
                    key = DeviceKey(principal.tenant, project['projectId'], str(sn))
                    if key not in keys:
                        keys.append(key)
                command = UFCommand('dev_sta', 'get', 'ehr_beta_upgrade', {'action': 'version'})
                replies = []
                deadline = time.monotonic() + 12
                for key in keys:
                    cached = registry.cached_read(key, command, ttl=300)
                    if cached is None:
                        future = registry.refresh(key, command)
                        if future is None:
                            return reply(code=503, message='正在检测测试版固件，请稍后刷新')
                        value = future.result(timeout=max(.01, deadline - time.monotonic()))
                    else:
                        value = cached['value']
                    try:
                        replies.append(upgrade_row(key.sn, value))
                    except BetaUnavailable as error:
                        return reply(code=503, message=str(error))
                return reply(replies)
''' + marker)
api.write_text(source, encoding='utf-8')
print('Original upgrade API contracts adapted; selected project enforced.')
