"""Read current project data through the pinned TLS relay without NAS SSH."""
import json
import time
from pathlib import Path
from local_api import account, post

proof = []
with account() as session:
    projects = post(session, 'apMonitor/getProjectList2', {})['dataList']
    for project in projects:
        body = {'projectId': project['projectId']}
        rows = post(session, 'homeAp/getStaList2', body)['sta_list']
        speed = post(session, 'experienceVisual/getApSpeedRate', body)
        stability = post(session, 'experienceVisual/getNetStabilizeIndex', body)
        selected = [{key: row.get(key) for key in ('mac', 'hostname', 'name', 'type',
                    'typeLevel2', 'manufacturer', 'online', 'isBlack', 'projectId')}
                    for row in rows if row.get('hostname') in ('fnos', 'DH4300PLUS-F6A9')]
        proof.append({'projectId': project['projectId'], 'name': project['projectName'],
            'online': sum(bool(row.get('online')) for row in rows), 'stations': len(rows),
            'curveBuckets': len(speed.get('bucketIndices', [])),
            'lastHourRx': speed.get('lastHourRx'), 'lastHourTx': speed.get('lastHourTx'),
            'stability': stability.get('curRadarScore'), 'identity': selected})

path = Path('test/_analysis/oct10-pages/pages-relay.json')
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps({'observedAt': time.time(), 'projects': proof},
                          ensure_ascii=False, indent=2), encoding='utf8')
for row in proof:
    print(json.dumps(row, ensure_ascii=False))
