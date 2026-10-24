"""Read current firmware sniffer switches and sampler history; no writes."""
import concurrent.futures
import datetime
import json
import re
import time
from pathlib import Path
from audit_application_usage import ROOT, read, json_read
from remote_transport import connect_router


def summarize_minutes(value):
    results = {}
    for key in ('deviceMinutes', 'appMinutes', 'traffic'):
        days = {}
        for row in value.get(key, []):
            day = days.setdefault(row.get('date'), {'devices': set(), 'apps': set(), 'minutes': 0, 'newest': 0})
            day['devices'].add(row.get('mac'))
            if row.get('app'):
                day['apps'].add(row['app'])
            minutes = row.get('minutes', [])
            day['minutes'] += len(minutes)
            day['newest'] = max(day['newest'], max(minutes, default=0))
        results[key] = {day: {**item, 'devices': len(item['devices']), 'apps': len(item['apps'])}
                        for day, item in sorted(days.items(), reverse=True)}
    results['lastSample'] = value.get('lastSample')
    return results


def probe(family):
    client = connect_router(family)
    try:
        expected = {'be72': 'G1TD8RX039025', 'be50': 'G1TQ88T702111'}[family]
        assert json_read(client, 'dev_sta get -m sysinfo').get('serial_num') == expected
        commands = {
            'methods': "ubus -t 3 -v list 'sniffer*'",
            'abilities': 'ubus -t 3 call sniffer show_abil',
            'snifferStatus': 'ubus -t 3 call sniffer show',
            'childGuardRuntime': 'dev_config get -m child_guard',
            'parameters': 'for f in /sys/module/sniffer/parameters/*; do printf "%s=" "$f"; cat "$f"; done',
            'rdpiHelp': 'rdpi -h',
            'serviceLogs': 'cat /tmp/debug/sniffer_monitor.log /tmp/debug/sniffer.log 2>/dev/null || true',
            'processStart': 'for p in $(pidof labrelay sniffer.elf); do readlink /proc/$p/exe; cat /proc/$p/stat; done',
            'log': 'logread | grep -E "minute|sniffer|rdpi|sampling|usage_stats" | tail -n 90',
            'configNames': "ls /etc/config/ | grep -E 'sniffer|child|rdpi'",
        }
        proof = {'family': family, 'observedAt': time.time()}
        for key, command in commands.items():
            proof[key] = read(client, command, 8)
        value = json_read(client, 'cat /tmp/labprobe_minute_store.json')
        proof['minutes'] = summarize_minutes(value)
        # Firmware logs can contain credentials in unrelated lines: persist the
        # filtered evidence locally, never print the arbitrary log contents.
        (ROOT / f'{family}-runtime.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf8')
        return {key: proof[key] for key in ('family', 'methods', 'parameters', 'rdpiHelp', 'processStart', 'minutes')}
    finally:
        client.close()


if __name__ == '__main__':
    ROOT.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for result in pool.map(probe, ('be72', 'be50')):
            print(json.dumps(result, ensure_ascii=False), flush=True)
