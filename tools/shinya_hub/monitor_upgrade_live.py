"""Read-only live capture of an in-flight firmware upgrade. Credentials via stdin JSON only."""
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, 'D:/Github/labprobe-hub')
from scripts.eweb_hub_remote import connect, run

SECRET = re.compile(r'(privateKey|registerKey|password|passwd|token|auth|secret|key)([\'"=:\s]+)([A-Za-z0-9_\-./=+]{6,})', re.I)


def scrub(text):
    return SECRET.sub(lambda m: m.group(1) + m.group(2) + '<redacted>', text)


def read(client, command):
    try:
        value = run(client, command, timeout=20)
    except Exception as error:
        return '<read failed: %s>' % type(error).__name__
    if isinstance(value, bytes):
        value = value.decode('utf8', errors='replace')
    return scrub(value)


COMMANDS = [
    ('now', 'date "+%F %T"; uptime'),
    ('version', 'cat /etc/openwrt_version 2>/dev/null'),
    ('betaStatus', 'cat /tmp/ehr_beta_upgrade_status 2>/dev/null || echo "<absent>"'),
    ('betaSpeed', 'cat /tmp/ehr_beta_upgrade_speed 2>/dev/null || echo "<absent>"'),
    ('betaPid', 'cat /tmp/ehr_beta_upgrade.pid 2>/dev/null || echo "<absent>"'),
    ('betaWrapper', 'cat /tmp/ehr_beta_upgrade_wrapper.sh 2>/dev/null || echo "<absent>"'),
    ('ownState', 'cat /tmp/shinya_upgrade/state 2>/dev/null || echo "<absent>"'),
    ('ownScript', 'cat /tmp/shinya_upgrade/run.sh 2>/dev/null || echo "<absent>"'),
    ('ownPid', 'cat /tmp/shinya_upgrade/worker.pid 2>/dev/null || echo "<absent>"'),
    ('package', 'ls -l /tmp/firmware.tar.gz /tmp/firmware.img /tmp/upgrade_img.bin 2>/dev/null; wc -c /tmp/firmware.tar.gz 2>/dev/null'),
    ('tmpFiles', 'ls -l /tmp/*.bin /tmp/*.tar.gz /tmp/*.img 2>/dev/null || echo "<none>"'),
    ('procs', 'ps w 2>/dev/null | grep -E "curl|sysupgrade|upgrade|sh /tmp" | grep -v grep'),
    ('mem', 'free 2>/dev/null; df -h /tmp 2>/dev/null'),
    ('uciUpgrade', 'uci show upgrade 2>/dev/null | grep -vE "privateKey|appid"'),
    ('uciBeta', 'uci show ehr_beta_upgrade 2>/dev/null | grep -vE "privateKey|appid"'),
    ('upgradeLog', 'tail -n 25 /tmp/log/upgrade.log 2>/dev/null || echo "<absent>"'),
    ('betaDebugLog', 'tail -n 25 /tmp/debug/ehr_beta_upgrade.log 2>/dev/null || echo "<absent>"'),
    ('betaShLog', 'tail -n 20 /tmp/ehr_beta_upgrade.log 2>/dev/null || echo "<absent>"'),
    ('ownLog', 'tail -n 20 /tmp/log/shinya-upgrade.log 2>/dev/null || echo "<absent>"'),
]


def sample(client, label):
    out = {'sample': label, 'at': time.time()}
    for name, command in COMMANDS:
        out[name] = read(client, command)
    return out


def main():
    creds = json.load(sys.stdin)
    client = connect(creds['host'], int(creds['port']), creds['user'], creds['password'])
    samples = []
    try:
        samples.append(sample(client, 't0'))
        time.sleep(6)
        samples.append(sample(client, 't1'))
    finally:
        try:
            client.close()
        except Exception:
            pass
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    payload = {'host': creds['host'], 'port': int(creds['port']), 'samples': samples, 'writesPerformed': False}
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    if out:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding='utf8')
    for s in samples:
        print('=== %s ===' % s['sample'])
        for key in ('now', 'version', 'betaStatus', 'betaSpeed', 'ownState', 'package', 'procs', 'mem'):
            print('--%s--\n%s' % (key, s[key]))
    if out:
        print('saved: %s' % out)


if __name__ == '__main__':
    main()