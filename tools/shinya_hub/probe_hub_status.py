import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    token = 'c3db47609dd11465778d15af80ecc77ad82f1efb19ea2402bb09e4a221908a8'
    for ep in ['/api/router/status', '/api/wireguard/server', '/api/router/devices', '/api/agent/cleanup/status']:
        cmd = f'curl -s -H "Authorization: Bearer {token}" http://192.168.5.46:58443{ep}'
        print(ep, ':', run(c, cmd).decode()[:200])
finally:
    c.close()
