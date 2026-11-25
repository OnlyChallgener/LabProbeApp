"""Apply only the offline wake/delete change to a captured production tree."""
from pathlib import Path
import shutil

root = Path('test/_analysis/offline-wake-live/runtime/shinya_hub')
local = Path('D:/Github/ShinyaHub/shinya_hub')

def once(text, old, new):
    assert text.count(old) == 1, old[:120]
    return text.replace(old, new, 1)

store = (root/'store.py').read_text(encoding='utf-8')
store = once(store, '            initialize_daily(db)', '''            db.execute("""CREATE TABLE IF NOT EXISTS station_deletions (
                tenant TEXT NOT NULL, project TEXT NOT NULL, mac TEXT NOT NULL,
                deleted REAL NOT NULL, PRIMARY KEY(tenant,project,mac))""")
            initialize_daily(db)''')
store = once(store, "                previous = db.execute('SELECT value FROM stations", """                deleted = db.execute('SELECT deleted FROM station_deletions WHERE tenant=? AND project=? AND mac=?',
                                     (tenant, project, mac)).fetchone()
                if deleted:
                    if seen <= deleted['deleted']: continue
                    db.execute('DELETE FROM station_deletions WHERE tenant=? AND project=? AND mac=?', (tenant, project, mac))
                previous = db.execute('SELECT value FROM stations""")
source = (local/'store.py').read_text(encoding='utf-8')
start = source.index('    def delete_offline_station(')
end = source.index('    def push_fresh(', start)
store = once(store, '    def push_fresh(', source[start:end] + '    def push_fresh(')
(root/'store.py').write_text(store,encoding='utf-8')

projection = (root/'projection.py').read_text(encoding='utf-8')
projection = once(projection, '    latest_macs = {}\n', '    latest_macs = {}\n    latest_observed = {}\n')
anchor = "        rows = snapshot['value'].get('list', []) if snapshot else []\n"
projection = once(projection, anchor, "        latest_observed[sn] = snapshot['observedAt'] if snapshot else 0\n"+anchor)
src=(local/'projection.py').read_text(encoding='utf-8')
begin=src.index('        from .station_actions import wake_eligible\n')
end=src.index('        if sn in guard_lists:',begin)
anchor='        project_choice(row,selected_identity.get(mac_key(raw[\'mac\'])))\n'
projection=once(projection,anchor,anchor+src[begin:end])
(root/'projection.py').write_text(projection,encoding='utf-8')

api=(root/'api.py').read_text(encoding='utf-8')
api=once(api,'from .daily import CHINA\n','from .daily import CHINA\nfrom .station_actions import target as station_action_target, offline_action, wake_eligible\n')
anchor='                        registry.cached_read(key, USER_LIST)\n'
api=once(api,anchor,anchor+"                        if principal.can_write:\n                            registry.cached_read(key, UFCommand('dev_sta','get','shinya_wol',None), ttl=60)\n")
src=(local/'api.py').read_text(encoding='utf-8')
begin=src.index("            if resource in {'remoteAccess/sendWol', 'homeAp/deleteInactiveSta'}:")
end=src.index("            if resource == 'sta/setStaRemark2':",begin)
block=src[begin:end].replace("if not can_manage(principal, raw):", "if not principal.can_write:")
block=block.replace("project = selected_project(principal, raw)", "project = {**selected_project(principal, raw), 'ownerTenant': principal.tenant}")
anchor="            if resource == 'sta/setStaRemark2':"
api=once(api,anchor,block+anchor)
(root/'api.py').write_text(api,encoding='utf-8')
shutil.copyfile(local/'station_actions.py',root/'station_actions.py')
print('Prepared production-based candidate')
