import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('=== /etc/labprobe/wireguard ===')
    print(run(c, 'ls -la /etc/labprobe/wireguard/; cat /etc/labprobe/wireguard/* 2>/dev/null || true').decode())
finally:
    c.close()
