"""Translate the firmware beta getter into the existing APP upgrade contract."""
import json


class BetaUnavailable(ValueError):
    pass


def upgrade_row(sn, value):
    if not isinstance(value, dict) or not isinstance(value.get('cur'), str) or not value['cur'].strip():
        raise BetaUnavailable('未取得当前固件版本')
    upcoming = value.get('new')
    if not isinstance(upcoming, dict):
        raise BetaUnavailable('测试版检测尚未返回有效结果')
    releases = upcoming.get('firmwareList')
    if isinstance(releases, str):
        try:
            releases = json.loads(releases)
        except (ValueError, TypeError):
            raise BetaUnavailable('测试版版本列表格式无效')
    if not isinstance(releases, (list, dict)):
        raise BetaUnavailable('测试版检测尚未返回版本列表')
    if isinstance(releases, dict):
        releases = [dict(v, version=v.get('version') or k) if isinstance(v, dict)
                    else {'version': k} for k, v in releases.items()]
    row = {'sn': sn, 'code': 2, 'softVersion': value['cur'].strip(),
           'updateInfo': '', 'updateInfoUrl': ''}
    if not releases:
        return row
    candidate = releases[0]  # Preserve the firmware service's ordering.
    if not isinstance(candidate, dict):
        candidate = {'version': candidate}
    version = candidate.get('version') or candidate.get('versionCode')
    if not isinstance(version, str) or not version.strip():
        raise BetaUnavailable('测试版版本信息不完整')
    notes = candidate.get('releaseNotes', candidate.get('release_notes', candidate.get('notes', '')))
    if isinstance(notes, list):
        notes = '\n'.join(str(line) for line in notes)
    row.update(code=6, softVersion=version.strip(), updateInfo=str(notes or ''),
               updateInfoUrl=str(candidate.get('updateInfoUrl') or ''))
    # Detection does not invent download credentials or change flash commands.
    return row
