"""Probe one GlobalSpeed node session using the recorded client token contract.

This performs the control-plane dovalid request only. It never downloads or
uploads test data. Client identity is freshly generated and BE72 identity is
never reused. The session token/key is deliberately discarded.
"""
import hashlib
import json
import socket
import time
import urllib.parse
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'test/be50-speedtest-20261024/platform-session-token-probe.json'
NODE = ROOT / 'test/be50-speedtest-20261024/platform-node-match-generic.json'
CONFIG = ROOT / 'test/be50-speedtest-20261024/source-reference/config.default.json'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--node-id', default='308000101')
    args = parser.parse_args()
    import secrets
    from hashlib import md5
    config = json.loads(CONFIG.read_text(encoding='utf8'))['cnspeed']
    rows = json.loads(NODE.read_text(encoding='utf8'))
    allowed = {str(row['id']) for row in json.loads(
        Path('D:/Github/ShinyaHub/test/be72-speedtest-20261023/servers.json').read_text(encoding='utf8'))['servers']}
    if args.node_id not in allowed:
        raise ValueError('Node ID is not in the recorded BE72 GlobalSpeed list')
    node = next(row for row in rows if str(row.get('hostid')) == args.node_id)
    token_cfg = config['token']
    imei = 'SH' + secrets.token_hex(8).upper()
    timestamp = int(time.time())
    first = token_cfg['part1'].replace('{imei}', imei)
    second = token_cfg['part2'].replace('{t}', str(timestamp))
    token = md5((md5(first.encode()).hexdigest() + md5(second.encode()).hexdigest()).encode()).hexdigest()
    query = urllib.parse.urlencode({
        'key': '', 'flag': 'true', 'bandwidth': '200', 'model': 'ShinyaHub',
        'imei': imei, 'time': timestamp, 'app': 'globalspeed', 'token': token,
        'pkg': 'cn.com.ruijie.shinya2',
    })
    request = ('GET /speed/dovalid?' + query + ' HTTP/1.1\r\n'
               'Host: ' + node['hostip'] + ':' + str(node['port']) + '\r\n'
               'User-Agent: ShinyaHub-GlobalSpeed/1.0\r\n'
               'Connection: close\r\n\r\n').encode()
    start = time.monotonic()
    result = {
        'nodeId': str(node['hostid']), 'model': 'ShinyaHub',
        'package': 'cn.com.ruijie.shinya2', 'app': 'globalspeed',
        'tokenGeneratedFromRecordedContract': bool(token),
        'measurementStarted': False, 'sessionKeyDiscarded': True,
    }
    try:
        with socket.create_connection((node['hostip'], int(node['port'])), timeout=6) as conn:
            conn.settimeout(6)
            conn.sendall(request)
            chunks = []
            total = 0
            while total < 16384:
                block = conn.recv(min(4096, 16384-total))
                if not block:
                    break
                chunks.append(block)
                total += len(block)
        raw = b''.join(chunks)
        body = raw.partition(b'\r\n\r\n')[2]
        if b'\r\n' in body:
            body = body.split(b'\r\n', 1)[-1]
            if b'\r\n' in body:
                body = body.split(b'\r\n', 1)[0]
        text = body.decode('utf8', errors='replace').strip()
        result.update({
            'httpStatus': raw.split(b'\r\n', 1)[0].decode('ascii', errors='replace')[:48],
            'bodyBytes': len(raw.partition(b'\r\n\r\n')[2]),
            'bodySHA256': hashlib.sha256(raw.partition(b'\r\n\r\n')[2]).hexdigest(),
            'sessionAccepted': bool(text.startswith('1-') and len(text) >= 15),
            'queueOrRejectedMarker': text == '-1-',
        })
    except Exception as exc:
        result['error'] = type(exc).__name__
    result['elapsedMs'] = round((time.monotonic()-start)*1000)
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
