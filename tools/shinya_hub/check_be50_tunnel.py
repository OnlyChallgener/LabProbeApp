import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 63829, 'ruijie', '123456')
try:
    print('Checking ssh tunnel processes on BE50:')
    print(run(c, 'ps w | grep -E "ssh"').decode())
    print('Checking /tmp/sshtunnel on BE50:')
    print(run(c, 'ls -la /tmp/sshtunnel 2>/dev/null || true').decode())
    print(run(c, 'cat /tmp/sshtunnel/* 2>/dev/null || true').decode())
    print('Checking /root/.ssh/id_rsa on BE50:')
    print(run(c, 'ls -la /root/.ssh/ 2>/dev/null || true').decode())
finally:
    c.close()
