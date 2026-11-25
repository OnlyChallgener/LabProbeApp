"""Print one line only when real upgrade activity appears on the device. Creds via stdin JSON."""
import json
import sys
import time

sys.path.insert(0, 'D:/Github/labprobe-hub')
from scripts.eweb_hub_remote import connect, run

PROBE = (
    'P=$(ls -l /tmp/firmware.tar.gz /tmp/firmware.img /tmp/upgrade_img.bin 2>/dev/null | head -3); '
    'S=$(cat /tmp/ehr_beta_upgrade_status 2>/dev/null); '
    'O=$(cat /tmp/shinya_upgrade/state 2>/dev/null); '
    'C=$(ps w 2>/dev/null | grep -E "curl|sysupgrade|ctc_upgrade|wrapper" | grep -v grep | head -3); '
    'U=$(uci -q get upgrade.recommend.upgradeState); '
    'echo "PKG=[$P] BETA=[$S] OWN=[$O] PROC=[$C] USTATE=[$U]"'
)


def main():
    creds = json.load(sys.stdin)
    deadline = time.time() + float(sys.argv[1] if len(sys.argv) > 1 else 600)
    last = None
    while time.time() < deadline:
        try:
            client = connect(creds['host'], int(creds['port']), creds['user'], creds['password'])
            try:
                value = run(client, PROBE, timeout=20)
            finally:
                client.close()
            line = value.decode('utf8', 'replace') if isinstance(value, bytes) else value
            line = ' '.join(line.split())
        except Exception as error:
            line = 'SSH-LOST %s' % type(error).__name__
        if line != last:
            print('%s %s' % (time.strftime('%H:%M:%S'), line), flush=True)
            last = line
        time.sleep(15)
    print('%s watch-ended' % time.strftime('%H:%M:%S'), flush=True)


if __name__ == '__main__':
    main()