"""Read firmware diagnostic support over the authorized router SSH links."""
import json
import argparse
import shlex
import time
from pathlib import Path
from remote_transport import connect_router, run


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--start', action='store_true')
    options = args.parse_args()
    proof = {}
    for family in ("be72", "be50"):
        client = connect_router(family)
        try:
            identity = json.loads(run(client, "dev_sta get -m sysinfo"))
            expected = {"be72": "G1TD8RX039025", "be50": "G1TQ88T702111"}[family]
            assert identity.get("serial_num") == expected
            raw = json.loads(run(client, "dev_sta get -m dev_diag"))
            before = raw
            started = None
            if options.start:
                started = json.loads(run(client, "dev_sta set -m dev_diag " +
                                         shlex.quote(json.dumps({'user': 'eweb', 'action': 'start'}))))
                print(json.dumps({'family': family, 'stage': 'started', 'rcode': started.get('rcode')}), flush=True)
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    time.sleep(2)
                    raw = json.loads(run(client, "dev_sta get -m dev_diag"))
                    progress = float(str(raw.get('process') or '0').rstrip('%'))
                    changed = raw.get('start_time') != before.get('start_time')
                    if changed and progress >= 100 and raw.get('rcode') == '00000000':
                        break
            proof[family] = {"observedAt": time.time(), "serial": expected, "result": raw,
                             "startResponse": started, "previousStartTime": before.get('start_time')}
            print(json.dumps({"family": family, "progress": raw.get("process"),
                              "groups": len(raw.get("list", [])), "keys": list(raw)}), flush=True)
        finally:
            client.close()
    output = Path("test/_analysis/oct10-pages/firmware-diagnosis.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding="utf8")


if __name__ == "__main__":
    main()
