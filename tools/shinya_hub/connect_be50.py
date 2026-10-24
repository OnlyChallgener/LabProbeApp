import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 63829, 'ruijie', '123456')
try:
    print('SUCCESS! Connected to BE50!')
    print('=== sysinfo ===')
    print(run(c, 'dev_sta get -m sysinfo').decode())
    print('=== ps processes ===')
    print(run(c, 'ps w | grep -iE "node|baby|sdwan|vpn" || true').decode())
    print('=== ip route ===')
    print(run(c, 'ip route').decode())
    print('=== ifconfig ===')
    print(run(c, 'ip -br addr').decode())
finally:
    c.close()
