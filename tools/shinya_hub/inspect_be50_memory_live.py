"""Read-only BE50 memory/process snapshots through the current SSH endpoint."""
import argparse, json, os, re, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import read_router_ipv6_sources  # configures existing credentials without printing them
from remote_transport import connect_router, run

MEM = "cat /proc/meminfo; cat /proc/loadavg; cat /proc/swaps"
PROCS = "for p in /proc/[0-9]*; do n=$(cat \"$p/comm\" 2>/dev/null); [ -n \"$n\" ] || continue; r=$(awk '/^VmRSS:/{print $2}' \"$p/status\" 2>/dev/null); s=$(awk '/^VmSwap:/{print $2}' \"$p/status\" 2>/dev/null); [ -n \"$r\" ] || r=0; [ -n \"$s\" ] || s=0; printf '%s %s %s %s\\n' \"${p##*/}\" \"$n\" \"$r\" \"$s\"; done"
SLABS = 'cat /proc/slabinfo'
NETWORK = 'cat /proc/net/sockstat; cat /proc/net/sockstat6; cat /proc/sys/net/netfilter/nf_conntrack_count 2>/dev/null; cat /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null'

def snapshot(connection):
    mem = run(connection, MEM, timeout=12).decode('utf-8', 'replace')
    process_raw = run(connection, PROCS, timeout=20).decode('utf-8', 'replace')
    values = {m.group(1): int(m.group(2)) for m in re.finditer(r'^(\w+):\s+(\d+) kB$', mem, re.M)}
    procs = []
    for line in process_raw.splitlines():
        fields = line.split()
        if len(fields) == 4 and fields[0].isdigit() and fields[2].isdigit() and fields[3].isdigit():
            procs.append({'pid': int(fields[0]), 'name': fields[1], 'rssKiB': int(fields[2]), 'swapKiB': int(fields[3])})
    procs.sort(key=lambda p: p['rssKiB'], reverse=True)
    slab_rows = []
    for line in run(connection, SLABS, timeout=12).decode('utf-8', 'replace').splitlines()[2:]:
        fields = line.split()
        if len(fields) >= 4 and all(x.isdigit() for x in fields[1:4]):
            slab_rows.append({'name': fields[0], 'activeKiB': int(fields[1])*int(fields[3])//1024,
                              'allocatedKiB': int(fields[2])*int(fields[3])//1024})
    slab_rows.sort(key=lambda row: row['allocatedKiB'], reverse=True)
    key_processes = [p for p in procs if any(s in p['name'].lower() for s in ('labprobe','shinya','agent','relay','node'))]
    for process in key_processes:
        pid = process['pid']
        try:
            details = run(connection, f'cat /proc/{pid}/smaps_rollup 2>/dev/null', timeout=8).decode('utf-8', 'replace')
            process['smapsKiB'] = {m.group(1): int(m.group(2)) for m in re.finditer(r'^(Rss|Pss|Private_Dirty|Shared_Clean|Anonymous):\s+(\d+) kB$', details, re.M)}
        except RuntimeError:
            details = run(connection, f'cat /proc/{pid}/status', timeout=8).decode('utf-8', 'replace')
            process['statusKiB'] = {m.group(1): int(m.group(2)) for m in re.finditer(r'^(VmRSS|RssAnon|RssFile|RssShmem|VmData):\s+(\d+) kB$', details, re.M)}
    for process in key_processes:
        pid = process['pid']
        process['fdCount'] = int(run(connection, f'ls /proc/{pid}/fd 2>/dev/null | wc -l', timeout=8).decode().strip())
    try:
        network = run(connection, NETWORK, timeout=8).decode('utf-8', 'replace').splitlines()
    except RuntimeError:
        network = []
    return {'time': int(time.time()), 'memKiB': values, 'top': procs[:20], 'agents': key_processes,
            'slabs': slab_rows[:20], 'network': network, 'load': mem.splitlines()[-2:]}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=63656)
    args = parser.parse_args()
    os.environ['SHINYA_BE50_HOST'] = '47.104.155.247'
    os.environ['SHINYA_BE50_PORT'] = str(args.port)
    c = connect_router('be50')
    try:
        identity = json.loads(run(c, 'dev_sta get -m sysinfo', timeout=12))
        assert identity.get('serial_num') == 'G1TQ88T702111', 'SSH endpoint is not the authorized BE50'
        result = snapshot(c)
        print(json.dumps(result, ensure_ascii=False, separators=(',', ':')))
    finally:
        c.close()

if __name__ == '__main__':
    main()
