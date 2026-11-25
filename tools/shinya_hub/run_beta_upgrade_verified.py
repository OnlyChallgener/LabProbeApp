"""One authorized config-preserving Beta upgrade via the vendor module, with read-only monitoring.

Credentials arrive as JSON on stdin only. The single write is the vendor
``dev_sta set -m ehr_beta_upgrade {"action":"upgrade","keepConfig":true}``;
everything else is observation.
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, 'D:/Github/labprobe-hub')
from scripts.eweb_hub_remote import connect, run

OUT = Path('D:/Github/LabProbeApp/test/_upgrade_live_20261117')


def read(client, command):
    value = run(client, command, timeout=20)
    return value.decode('utf8', 'replace') if isinstance(value, bytes) else value


def try_connect(creds):
    return connect(creds['host'], int(creds['port']), creds['user'], creds['password'])


def snapshot(client):
    return {
        'version': read(client, 'cat /etc/openwrt_version 2>/dev/null || true').strip(),
        'uptime': read(client, 'uptime').strip(),
        'hostname': read(client, 'uci -q get system.@system[0].hostname 2>/dev/null || true').strip(),
        'ownedLua': read(client, 'ls /usr/local/lua/dev_sta/shinya_exec.lua 2>/dev/null || echo MISSING').strip(),
        'agentConfig': read(client, 'ls /etc/labprobe/agent.json 2>/dev/null || echo MISSING').strip(),
        'wifiSsid': read(client, 'uci -q get wireless.@wifi-iface[0].ssid 2>/dev/null || true').strip(),
    }


def main():
    creds = json.load(sys.stdin)
    log = []

    def note(tag, text):
        line = '%s %s %s' % (time.strftime('%H:%M:%S'), tag, ' '.join(str(text).split()))
        print(line, flush=True)
        log.append({'at': time.time(), 'tag': tag, 'text': str(text)})

    client = try_connect(creds)
    before = snapshot(client)
    note('PRE', json.dumps(before, ensure_ascii=False))
    ack = read(client, 'dev_sta set -m ehr_beta_upgrade \'{"action":"upgrade","keepConfig":true}\' 2>&1 || true')
    note('ACK', ack)
    client.close()
    if '"code":0' not in ack.replace(' ', '') and '"code": 0' not in ack:
        note('ABORT', 'vendor module did not accept the upgrade')
        return

    last = None
    deadline = time.time() + 600
    flashing = False
    while time.time() < deadline:
        try:
            client = try_connect(creds)
            try:
                state = read(client, 'cat /tmp/ehr_beta_upgrade_status 2>/dev/null || echo NOSTATUS')
                size = read(client, 'wc -c < /tmp/firmware.tar.gz 2>/dev/null || echo 0')
            finally:
                client.close()
        except Exception as error:
            note('SSH-LOST', type(error).__name__)
            flashing = True
            break
        line = '%s bytes=%s' % (state.strip(), size.strip())
        if line != last:
            note('PROGRESS', line)
            last = line
        if '"status":"upgrading"' in state.replace(' ', '') or '"state":113' in state.replace(' ', ''):
            note('FLASHING', 'device entered sysupgrade; expecting reboot')
            flashing = True
            break
        if '"status":"error"' in state.replace(' ', ''):
            note('FAILED', state)
            return
        time.sleep(8)

    if not flashing:
        note('TIMEOUT', 'no flashing state within 600s')
        return

    note('WAIT', 'polling for reconnect')
    deadline = time.time() + 480
    after = None
    while time.time() < deadline:
        time.sleep(10)
        try:
            client = try_connect(creds)
            try:
                after = snapshot(client)
            finally:
                client.close()
            break
        except Exception:
            continue
    if after is None:
        note('NO-RECONNECT', 'device did not come back within 480s')
    else:
        note('POST', json.dumps(after, ensure_ascii=False))
        kept = (before['hostname'] == after['hostname']
                and before['wifiSsid'] == after['wifiSsid']
                and after['ownedLua'] != 'MISSING' and after['agentConfig'] != 'MISSING')
        note('VERDICT', json.dumps({
            'versionChanged': before['version'] != after['version'],
            'configPreserved': kept,
            'before': before['version'], 'after': after['version']}, ensure_ascii=False))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / ('flash-%s.json' % time.strftime('%Y%m%d-%H%M%S'))).write_text(
        json.dumps({'log': log, 'writes': ['vendor beta upgrade keepConfig=true']}, ensure_ascii=False, indent=2),
        encoding='utf8')


if __name__ == '__main__':
    main()