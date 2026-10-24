"""Install the audited diagnosis adapter without overwriting other HUB changes."""
from pathlib import Path
import shutil

root = Path('D:/Github/ShinyaHub')
stage = Path(__file__).parent / 'staged_diagnosis'
for source in stage.glob('*.py'):
    target = root / ('tests' if source.name.startswith('test_') else 'shinya_hub') / source.name
    shutil.copyfile(source, target)

path = root / 'shinya_hub/api.py'
text = path.read_text(encoding='utf8')
if 'from .diagnosis import DiagnosticJobs' not in text:
    text = text.replace('from .beta import BetaUnavailable, upgrade_row',
                        'from .beta import BetaUnavailable, upgrade_row\nfrom .diagnosis import DiagnosticJobs, RESOURCE as DIAGNOSIS_RESOURCE')
    text = text.replace('    jobs = ReadJobs()', '    jobs = ReadJobs()\n    diagnosis = DiagnosticJobs(registry)\n    app.extensions["shinya_diagnosis"] = diagnosis')
    anchor = "            if resource == 'homeAp/getBetaStatus':"
    assert text.count(anchor) == 1
    text = text.replace(anchor, '''            if resource == 'hub/diagnosis':
                if request.method != 'POST':
                    return reply(code=405, message='诊断请求方式无效')
                project = selected_project(principal, raw)
                main = next((d for d in project['apList'] if d.get('isMain')),
                            project['apList'][0] if project['apList'] else None)
                if not main: raise LookupError('项目尚未绑定路由器')
                key = selected_device(principal, {**raw, 'apSn': raw.get('apSn') or main['sn']})
                operation = raw.get('operation', 'status')
                if operation == 'start':
                    if not principal.can_write: raise PermissionError('当前账号没有诊断权限')
                    return reply(diagnosis.start(key))
                if operation == 'status':
                    return reply(diagnosis.status(key, str(raw.get('jobId') or '')))
                if operation == 'last':
                    cached = store.cached(key.tenant, key.project, key.sn, DIAGNOSIS_RESOURCE)
                    return reply({'report': cached['value'] if cached else None,
                                  'observedAt': cached['observedAt'] if cached else None})
                raise ValueError('诊断操作无效')
''' + anchor)
    path.write_text(text, encoding='utf8')
path = root / 'shinya_hub/runtime.py'
text = path.read_text(encoding='utf8')
if 'app.extensions["shinya_diagnosis"].close()' not in text:
    text = text.replace('        stopping.set()\n        registry.close()',
                        '        stopping.set()\n        app.extensions["shinya_diagnosis"].close()\n        registry.close()')
    path.write_text(text, encoding='utf8')
print('Project-scoped firmware diagnosis adapter installed.')

# Add cancellation to existing installations without replacing other routes.
path = root / 'shinya_hub/api.py'
text = path.read_text(encoding='utf8')
if 'diagnosis.cancel(key' not in text:
    anchor = "                if operation == 'last':\n                    cached = store.cached(key.tenant, key.project, key.sn, DIAGNOSIS_RESOURCE)"
    assert text.count(anchor) == 1
    text = text.replace(anchor, "                if operation == 'cancel':\n                    if not principal.can_write: raise PermissionError('当前账号没有诊断权限')\n                    return reply(diagnosis.cancel(key, str(raw.get('jobId') or '')))\n" + anchor)
    path.write_text(text, encoding='utf8')
