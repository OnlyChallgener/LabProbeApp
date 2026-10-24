"""Install the project-bound STUN adapter and beta getter into standalone HUB."""
from pathlib import Path
import shutil

root = Path('D:/Github/ShinyaHub')
stage = Path(__file__).parent / 'staged_account/shinya_hub'
shutil.copy2(stage / 'stun_bridge.py', root / 'shinya_hub/stun_bridge.py')
shutil.copy2(stage / 'local_service.py', root / 'shinya_hub/local_service.py')
shutil.copy2(stage.parent / 'tests/test_stun_routes.py', root / 'tests/test_stun_routes.py')
api = root / 'shinya_hub/api.py'
text = api.read_text(encoding='utf-8')
if 'from .stun_bridge import StunError' not in text:
    text = text.replace('from .auth import AccountStore, AuthError',
                        'from .auth import AccountStore, AuthError\nfrom .stun_bridge import StunError')
    text = text.replace('        except AuthError as error:',
                        '        except StunError as error:\n            return reply(code=error.code, message=str(error))\n        except AuthError as error:')
text = text.replace('accounts: AccountStore | None = None, allow_legacy_tokens=True):',
                    'accounts: AccountStore | None = None, allow_legacy_tokens=True, stun_bridge=None):')
anchor = "            if resource == 'apMonitor/getAbility':"
new = '''            if resource == 'hub/stun':
                if request.method != 'POST':
                    return reply(code=405, message='STUN 请求方式无效')
                key = selected_device(principal, raw)
                operation = raw.get('operation', 'list')
                if operation not in {'list', 'addresses'} and not principal.can_write:
                    raise PermissionError('当前账号没有配置权限')
                if stun_bridge is None:
                    return reply(code=501, message='STUN 执行服务尚未配置')
                return reply(stun_bridge.request(key, operation, str(raw.get('ruleId') or ''), raw.get('draft')))
            if resource == 'hub/beta':
                key = selected_device(principal, raw)
                command = UFCommand('dev_sta', 'get', 'ehr_beta_upgrade', {'action': 'version'})
                if raw.get('force') in ('1', 1, True):
                    future = registry.refresh(key, command)
                    if future is not None:
                        future.result(timeout=12)
                cached = registry.cached_read(key, command, ttl=300)
                if cached is None:
                    return reply(code=503, message='正在检测测试版固件，请稍后刷新')
                return reply(cached['value'], cachedAt=cached['observedAt'], channel='beta')
'''
if "resource == 'hub/stun'" not in text:
    assert text.count(anchor) == 1
    text = text.replace(anchor, new + anchor)
api.write_text(text, encoding='utf-8')
runtime = root / 'shinya_hub/runtime.py'
text = runtime.read_text(encoding='utf-8')
text = text.replace('accounts=accounts, allow_legacy_tokens=',
                    'accounts=accounts if settings.get("accountLoginEnabled", True) else None, allow_legacy_tokens=')
if 'from .stun_bridge import StunBridge' not in text:
    text = text.replace('from .auth import AccountStore', 'from .auth import AccountStore\nfrom .stun_bridge import StunBridge')
    text = text.replace('allow_legacy_tokens=settings.get("allowLegacyTokens", True))',
                        'allow_legacy_tokens=settings.get("allowLegacyTokens", True),\n'
                        '                     stun_bridge=StunBridge(settings.get("stunBindings", [])))')
runtime.write_text(text, encoding='utf-8')
print('STUN adapter and beta detection installed')
