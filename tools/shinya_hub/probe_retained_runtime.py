"""Read request paths, ownership metadata and existing child bridge status safely."""
import json, os, sys, re
from pathlib import Path
sys.path.insert(0, 'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect, run

password = os.environ['SHINYA_NAS_PASSWORD']
client = connect(os.environ['SHINYA_NAS_HOST'], int(os.environ['SHINYA_NAS_PORT']),
                 os.environ['SHINYA_NAS_USER'], password)
try:
    logs = run(client, 'docker logs --tail 150 shinya-hub', sudo_password=password)
    paths = []
    for line in logs.splitlines():
        match = re.search(r'(GET|POST) ([^ ?]+)', line)
        if match:
            paths.append({'method': match[1], 'path': match[2]})
    print(json.dumps({'recentPaths': paths[-60:]}))
    program = r'''
import json, os, requests
from pathlib import Path
from official_child_guard import OfficialGuardClient
from assistant.security import decrypt_secret_with_migration
from router_rpc import EncryptedRouterConfigStore
c=EncryptedRouterConfigStore(Path('/app/config')).load()
paths=[Path(os.environ.get('DATA_DIR','/app/data'))/'official_child_guard_accounts.json',Path('/data/official_child_guard_accounts.json')]
p=next((p for p in paths if p.is_file()),None)
bindings=[json.loads(decrypt_secret_with_migration(v)[0]) for v in json.loads(p.read_text()).values()] if p else []
print(json.dumps({'port': os.environ.get('PORT'), 'address': c.get('address'),
 'bindings': [{k:b.get(k) for k in ('serial','projectId','projectName','createTime')} for b in bindings]}))
'''
    for container in ('labprobe-hub', 'labprobe-hub-be50'):
        result = json.loads(run(client, 'docker exec -i '+container+' python -',
                 stdin=program.encode(), sudo_password=password, timeout=35))
        print(json.dumps({'container': container, **result}))
finally:
    client.close()
