"""Restore a stopped BE72 identifier only when existing config requires it."""
import hashlib
import json
import shlex
import time
from audit_application_usage import ROOT
from remote_transport import connect_router, run


if __name__ == '__main__':
    client = connect_router('be72')
    try:
        identity = json.loads(run(client, 'dev_sta get -m sysinfo'))
        assert identity.get('serial_num') == 'G1TD8RX039025'
        configured = run(client, 'uci -q get child_guard.config.rdpi_enable').strip()
        assert configured == b'1', 'Existing child configuration does not request RDPI'
        before = json.loads(run(client, 'ubus -t 3 call sniffer show_abil'))
        library_before = hashlib.sha256(run(client, 'cat /usr/share/ndpi/db.default.json')).hexdigest()
        result = None
        if before.get('RDPI') == 0:
            result = json.loads(run(client, 'ubus -t 3 call sniffer enable ' + shlex.quote('{"mod":"rdpi"}')))
        time.sleep(5)
        after = json.loads(run(client, 'ubus -t 3 call sniffer show_abil'))
        library_after = hashlib.sha256(run(client, 'cat /usr/share/ndpi/db.default.json')).hexdigest()
        assert after.get('RDPI', 0) > 0, 'RDPI remained stopped'
        assert library_before == library_after, 'Feature library changed unexpectedly'
        proof = {'observedAt': time.time(), 'serial': identity['serial_num'],
                 'before': before, 'response': result, 'after': after,
                 'librarySha256Before': library_before, 'librarySha256After': library_after}
        ROOT.mkdir(parents=True, exist_ok=True)
        (ROOT / 'be72-runtime-recovery.json').write_text(json.dumps(proof, indent=2), encoding='utf8')
        print(json.dumps(proof))
    finally:
        client.close()
