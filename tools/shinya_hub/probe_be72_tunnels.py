import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('=== BE72 ssh tunnel processes ===')
    print(run(c, 'ps w | grep -E "[s]sh|[t]unnel" || true').decode())
    print('=== BE72 /tmp/sshtunnel ===')
    print(run(c, 'ls -la /tmp/sshtunnel/ 2>/dev/null || true').decode())
    print(run(c, 'cat /tmp/sshtunnel/* 2>/dev/null || true').decode())
finally:
    c.close()
