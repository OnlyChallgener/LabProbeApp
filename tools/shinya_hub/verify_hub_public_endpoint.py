"""Compare public TLS identity with the authorized NAS relay before health GET."""
import hashlib
import json
import socket
import ssl


def tls(host, port):
    # These HUB certificates are private. Verify the exact peer certificate
    # against the existing authorized SSH path before sending HTTP bytes.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context.wrap_socket(socket.create_connection((host, port), timeout=12), server_hostname=host)


with tls('127.0.0.1', 15846) as trusted:
    expected = hashlib.sha256(trusted.getpeercert(binary_form=True)).digest()
with tls('47.104.155.247', 64446) as public:
    actual = hashlib.sha256(public.getpeercert(binary_form=True)).digest()
    if actual != expected:
        raise RuntimeError('Public endpoint has a different HUB TLS identity')
    public.sendall(b'GET /healthz HTTP/1.1\r\nHost: 47.104.155.247:64446\r\nConnection: close\r\n\r\n')
    data = b''
    while True:
        block = public.recv(4096)
        if not block:
            break
        data += block
        if len(data) > 65536:
            raise RuntimeError('Unexpected health response size')
    head, body = data.split(b'\r\n\r\n', 1)
    value = json.loads(body)
    assert head.startswith(b'HTTP/1.1 200') and value.get('service') == 'shinya-hub' and value.get('ok')
    print(json.dumps({'tlsIdentityMatches': True, 'healthy': True, 'service': value['service']}))
