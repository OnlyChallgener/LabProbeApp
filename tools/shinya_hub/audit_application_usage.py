"""Read-only evidence for RDPI identification and both NAS usage databases.

Credentials come only from the existing SSH environment. No reload, enable,
restart, import, or database mutation is performed here.
"""
import concurrent.futures
import datetime
import hashlib
import json
import os
import re
import shlex
import time
from pathlib import Path

from remote_transport import connect_nas, connect_router, run

ROOT = Path('test/_analysis/oct10-usage-incident')
APPID = re.compile(r'^\d+-\d+-\d+-\d+$')
MAC = re.compile(r'^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$')


def read(client, command, timeout=12):
    try:
        return run(client, command, timeout=timeout).decode('utf8', errors='replace')
    except Exception as error:
        return {'error': type(error).__name__}


def json_read(client, command):
    value = read(client, command)
    if isinstance(value, dict):
        return value
    try:
        return json.loads(value)
    except ValueError:
        return {'error': 'invalid JSON', 'bytes': len(value)}


def tree_summary(value, depth=0):
    if isinstance(value, dict):
        if depth >= 3:
            return {'count': len(value), 'sampleKeys': list(value)[:4]}
        return {key: tree_summary(item, depth + 1) for key, item in list(value.items())[:20]}
    if isinstance(value, list):
        return {'count': len(value), 'sample': [tree_summary(x, depth + 1) for x in value[:2]]}
    return value


def router(family):
    client = connect_router(family)
    try:
        identity = json_read(client, 'dev_sta get -m sysinfo')
        expected = {'be72': 'G1TD8RX039025', 'be50': 'G1TQ88T702111'}[family]
        assert identity.get('serial_num') == expected, 'Router identity mismatch'
        proof = {'observedAt': time.time(), 'serial': expected,
                 'clock': read(client, "date '+%Y-%m-%d %H:%M:%S %z'; cat /proc/uptime")}
        library = read(client, 'cat /usr/share/ndpi/db.default.json')
        if isinstance(library, str):
            raw = json.loads(library)
            apps = raw.get('apps', [])
            ids = [str(a.get('index')) for a in apps]
            invalid_host_rules = []
            for app in apps:
                for index, rule in enumerate(app.get('rules', [])):
                    if rule.get('protocol') == 'host' and rule.get('payloads') != []:
                        invalid_host_rules.append({'appid': app.get('index'), 'rule': index})
            proof['library'] = {'sha256': hashlib.sha256(library.encode()).hexdigest(),
                                'bytes': len(library.encode()), 'rootKeys': list(raw),
                                'apps': len(apps), 'uniqueIds': len(set(ids)),
                                'customApps': sum(x.startswith('9-') for x in ids),
                                'invalidHostRules': invalid_host_rules,
                                'file': read(client, 'ls -l /usr/share/ndpi/db.default.json')}
            (ROOT / f'{family}-feature-library.json').write_text(library, encoding='utf8')
        else:
            proof['library'] = library
        table = read(client, 'rdpi -t', 20)
        if isinstance(table, str):
            loaded = {line.split()[-1] for line in table.splitlines()
                      if line.split() and APPID.fullmatch(line.split()[-1])}
            proof['loadedApps'] = {'count': len(loaded), 'missingFromLoaded': sorted(set(ids) - loaded),
                                   'sha256': hashlib.sha256(table.encode()).hexdigest()}
            (ROOT / f'{family}-loaded-apps.txt').write_text(table, encoding='utf8')
        else:
            proof['loadedApps'] = table
        sniffer_info = read(client, 'cat /proc/net/sniffer_info')
        proof['snifferInfo'] = sniffer_info
        for name in ('sniffer_flow', 'sniffer_flow_full'):
            flow = read(client, 'cat /proc/net/' + name)
            if isinstance(flow, str):
                rows = [line.split() for line in flow.splitlines() if len(line.split()) >= 15
                        and MAC.fullmatch(line.split()[0])]
                identified = [row for row in rows if row[7] != '0-0-0-0']
                proof[name] = {'rows': len(rows), 'identified': len(identified),
                               'deviceCount': len({r[0] for r in rows}),
                               'identifiedAppids': sorted({r[7] for r in identified}),
                               'bytesUp': sum(int(r[11]) for r in rows if r[11].isdigit()),
                               'bytesDown': sum(int(r[13]) for r in rows if r[13].isdigit())}
                (ROOT / f'{family}-{name}.txt').write_text(flow, encoding='utf8')
            else:
                proof[name] = flow
        proof['processes'] = read(client,
            "for n in sniffer.elf labrelay shinya-agent; do printf '%s ' \"$n\"; "
            "pidof \"$n\" || true; done; "
            "for p in $(pidof sniffer.elf); do cat /proc/$p/stat; done")
        proof['ubusObjects'] = read(client, "ubus -t 3 list 'sniffer*'", 5)
        proof['childGuardConfig'] = read(client, 'uci -q export child_guard')
        minute = json_read(client, 'cat /tmp/labprobe_minute_store.json')
        proof['minuteStore'] = tree_summary(minute)
        (ROOT / f'{family}-minute-store.json').write_text(json.dumps(minute, ensure_ascii=False), encoding='utf8')
        cached = read(client, 'cat /tmp/labprobe_rdpi_table.txt')
        proof['cachedAppMap'] = {'lines': len(cached.splitlines())} if isinstance(cached, str) else cached
        (ROOT / f'{family}-audit.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf8')
        return {'family': family, 'library': proof['library'], 'loadedApps': proof['loadedApps'],
                'flows': proof.get('sniffer_flow_full'), 'processes': proof['processes'],
                'snifferInfo': sniffer_info, 'minuteStore': proof['minuteStore']}
    finally:
        client.close()


DB_SCRIPT = r'''
import glob,json,sqlite3,time,datetime
out={'time':time.time(),'databases':{}}
for path in set(glob.glob('/app/data/*.db')+glob.glob('/data/*.sqlite')+glob.glob('/data/*.db')):
 try:
  db=sqlite3.connect('file:'+path+'?mode=ro',uri=True)
  db.row_factory=sqlite3.Row
  tables={row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
  results={}
  for table in ('usage_device_minute','usage_app_minute','usage_device_traffic','usage_daily_app'):
   if table not in tables:continue
   cols={row[1] for row in db.execute('PRAGMA table_info('+table+')')}
   group='router,date' if 'router' in cols else 'date'
   extra=',count(distinct app) apps' if 'app' in cols else ''
   extra+=',max(minute_epoch) newestMinute' if 'minute_epoch' in cols else ''
   query='SELECT '+group+',count(*) rows,count(distinct mac) devices,max(updated_at) newestUpdate'+extra+' FROM '+table+' GROUP BY '+group+' ORDER BY date DESC LIMIT 14'
   results[table]=[dict(row) for row in db.execute(query)]
  if 'usage_ingest_meta' in tables:
   results['usage_ingest_meta']=[dict(row) for row in db.execute('SELECT router,count(*) devices,max(last_sample_at) newestSample,max(updated_at) newestUpdate FROM usage_ingest_meta GROUP BY router')]
  if 'child_guard_device' in tables:
   results['child_guard_device']=[dict(row) for row in db.execute('SELECT router,count(*) members,max(updated_at) newestUpdate FROM child_guard_device GROUP BY router')]
  if 'push_transport' in tables:
   results['push_transport']=[dict(row) for row in db.execute('SELECT * FROM push_transport')]
  out['databases'][path]=results
  db.close()
 except Exception as e:out['databases'][path]={'error':type(e).__name__}
print(json.dumps(out))
'''


def nas():
    client = connect_nas()
    try:
        password = os.environ['SHINYA_NAS_PASSWORD']
        listing = run(client, "docker ps --format '{{.Names}} {{.Image}} {{.Status}}'", sudo_password=password).decode()
        proof = {'observedAt': time.time(), 'containers': listing, 'usage': {}}
        for line in listing.splitlines():
            name = line.split()[0]
            if not any(x in name for x in ('labprobe-hub', 'shinya-hub')):
                continue
            try:
                result = run(client, 'docker exec ' + shlex.quote(name) + ' python -c ' + shlex.quote(DB_SCRIPT),
                             sudo_password=password, timeout=25)
                proof['usage'][name] = json.loads(result)
            except Exception as error:
                proof['usage'][name] = {'error': type(error).__name__}
        (ROOT / 'nas-audit.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf8')
        return proof
    finally:
        client.close()


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {pool.submit(router, family): family for family in ('be72', 'be50')}
        jobs[pool.submit(nas)] = 'nas'
        for job in concurrent.futures.as_completed(jobs):
            try:
                result = job.result()
                print(json.dumps(result, ensure_ascii=False), flush=True)
            except Exception as error:
                print(json.dumps({'target': jobs[job], 'error': type(error).__name__}), flush=True)


if __name__ == '__main__':
    main()
