"""Read retained-card capability values and query contracts from the real NAS."""
import json, warnings
from pathlib import Path
from local_api import account, post, BASE

warnings.filterwarnings('ignore')
proof = []
with account() as session:
    for project in post(session, 'apMonitor/getProjectList2', {})['dataList']:
        pid = project['projectId']
        rows = post(session, 'homeAp/getDeviceInfoList2', {'projectId': pid})['apList']
        for row in rows:
            sn = row['sn']
            selected = {k: row.get(k) for k in ('sn', 'isOnline', 'childGuardVer', 'supportLanInfo', 'forwardMode', 'assurance', 'capabilities')}
            result = {'projectId': pid, 'device': selected}
            reply = session.get(BASE+'netguard/staGuard/getProtectStaListPage',
                params={'mainApSn': sn, 'pageIndex': 1, 'pageSize': 10}, timeout=20).json()
            result['originalChildQuery'] = {'code': reply['code'], 'message': reply.get('msg'),
                'rows': len((reply.get('data') or {}).get('rows', []))}
            proof.append(result)
            print(json.dumps(result, ensure_ascii=True), flush=True)
Path('test/_analysis/oct10-pages/card-runtime.json').write_text(
    json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf8')
