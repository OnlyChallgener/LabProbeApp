"""Restart only the owned broker and verify that real native push resumes."""
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, 'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import run
from remote_transport import connect_nas

PROBE = r'''
import json,time
from pathlib import Path
from shinya_hub.store import ProjectStore
store=ProjectStore(Path('/data/shinya.sqlite'))
with store.connect() as db:
    rows=db.execute('SELECT sn,sequence,observed FROM push_transport ORDER BY sn').fetchall()
print(json.dumps([{'sn':r['sn'],'sequence':r['sequence'],'observed':r['observed'],
                  'ageSeconds':round(time.time()-r['observed'],2)} for r in rows]))
'''


def main():
    client = connect_nas()
    password = os.environ['SHINYA_NAS_PASSWORD']
    def state():
        return json.loads(run(client, 'docker exec -i shinya-hub python -',
                              stdin=PROBE.encode(), sudo_password=password))
    try:
        before = state()
        if len(before) != 2 or any(v['ageSeconds'] > 15 for v in before):
            raise RuntimeError('Both router feeds must be fresh before the restart')
        run(client, 'docker restart shinya-mqtt', sudo_password=password, timeout=25)
        restarted = time.time()
        snapshots = []
        for _ in range(6):
            time.sleep(5)
            after = state()
            snapshots.append(after)
            prior = {r['sn']: r for r in before}
            if len(after) == 2 and all(v['sn'] in prior and v['observed'] > prior[v['sn']]['observed']
                    and v['sequence'] > prior[v['sn']]['sequence'] and v['ageSeconds'] < 15 for v in after):
                result = {'scope':'owned-broker-restart-real-routers', 'passed':True,
                          'recoveryWithinSeconds':round(time.time()-restarted,2),
                          'before':before,'after':after,'snapshots':snapshots}
                break
        else:
            result = {'scope':'owned-broker-restart-real-routers','passed':False,
                      'before':before,'snapshots':snapshots}
        target = Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/cloud-sync/broker-recovery.json'
        target.write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result),flush=True)
        if not result['passed']:
            raise RuntimeError('The router feeds did not resume within the observation window')
    finally:
        client.close()


if __name__ == '__main__':
    main()
