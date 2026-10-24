"""Read only the native wireless power on each owned router."""
import json
import sys
from pathlib import Path
from remote_transport import connect_router, run

def read_powers():
    result = {}
    for family, sn in [('be72', 'G1TD8RX039025'), ('be50', 'G1TQ88T702111')]:
        client = connect_router(family)
        try:
            identity = json.loads(run(client, 'dev_sta get -m sysinfo'))
            assert identity['serial_num'] == sn
            value = json.loads(run(client, 'dev_config get -m radio'))
            while isinstance(value, dict) and 'rcode' in value and 'data' in value:
                value = value['data']
                if isinstance(value, str): value = json.loads(value)
            result[family] = [{k: row.get(k) for k in ('radioIndex', 'type', 'txpower', 'channel')}
                              for row in value['radioList']]
        finally:
            client.close()
    return result

if __name__ == '__main__':
    result = read_powers()
    print(json.dumps(result, ensure_ascii=False))
    if len(sys.argv) == 2:
        Path(sys.argv[1]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
