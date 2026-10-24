"""Deploy the read-only STUN status correction to the existing BE72 Hub container.

The router and Relay processes are not restarted. The previous Hub module is
kept on the NAS so a failed health/status check can restore it immediately.
"""
import hashlib
import json
import os
from pathlib import Path

from remote_transport import connect_nas, run
from eweb_hub_remote import write


SOURCE = Path('D:/Github/labprobe-hub/stun_service.py')
EXPECTED_OLD = 'f5420f4532ebf90728932eb00e076f4fb6c8c438de18a0b5cc07e67d8dce7305'
STAGED = '/tmp/labprobe-stun-status-readonly.py'
BACKUP = '/tmp/labprobe-stun-status-before.py'


def remote_hash(client):
    return run(client, 'docker exec labprobe-hub sha256sum /app/stun_service.py',
               sudo_password=os.environ['SHINYA_NAS_PASSWORD'], timeout=12).decode().split()[0]


def check(client):
    probe = '''import json,requests,hub,time
s=requests.Session();s.trust_env=False;s.headers['Authorization']='Bearer '+hub.get_app_token()
for attempt in range(30):
 try:
  h=s.get('http://127.0.0.1:58443/health',timeout=3)
  h.raise_for_status()
  break
 except requests.RequestException:
  if attempt==29:raise
  time.sleep(1)
v=s.get('http://127.0.0.1:58443/api/stun',timeout=18).json()
for r in v.get('rules',[]):
 if r.get('id') in ('stun-e06aa62d8580','stun-9f678cf1fe94'):
  print(json.dumps({'id':r['id'],'state':r.get('actualState'),'firewall':r.get('firewallState'),'owner':r.get('firewallOwner'),'fresh':(r.get('runtime') or {}).get('mappingFresh')}))
'''
    output = run(client, 'docker exec -i labprobe-hub python -', stdin=probe.encode(),
                 sudo_password=os.environ['SHINYA_NAS_PASSWORD'], timeout=35).decode()
    rows = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
    if len(rows) != 2 or any(row['firewall'] != 'ready' or row['owner'] != 'untracked' for row in rows):
        raise RuntimeError('The live STUN rules did not pass the read-only firewall match: ' + json.dumps(rows))
    return rows


def main():
    client = connect_nas()
    password = os.environ['SHINYA_NAS_PASSWORD']
    changed = False
    try:
        if remote_hash(client) != EXPECTED_OLD:
            raise RuntimeError('Running STUN module differs from the reviewed version')
        source = SOURCE.read_bytes()
        write(client, STAGED, source)
        run(client, 'docker cp labprobe-hub:/app/stun_service.py ' + BACKUP,
            sudo_password=password, timeout=20)
        run(client, 'docker cp ' + STAGED + ' labprobe-hub:/app/stun_service.py',
            sudo_password=password, timeout=20)
        changed = True
        if remote_hash(client) != hashlib.sha256(source).hexdigest():
            raise RuntimeError('Staged STUN module checksum mismatch')
        run(client, 'docker exec labprobe-hub python -m py_compile /app/stun_service.py',
            sudo_password=password, timeout=20)
        run(client, 'docker restart labprobe-hub', sudo_password=password, timeout=75)
        rows = check(client)
        print(json.dumps({'result': 'deployed', 'rules': rows, 'backup': BACKUP}))
    except Exception:
        if changed:
            run(client, 'docker cp ' + BACKUP + ' labprobe-hub:/app/stun_service.py',
                sudo_password=password, timeout=20)
            run(client, 'docker restart labprobe-hub', sudo_password=password, timeout=75)
            print(json.dumps({'result': 'rolled_back'}))
        raise
    finally:
        client.close()


if __name__ == '__main__':
    main()
