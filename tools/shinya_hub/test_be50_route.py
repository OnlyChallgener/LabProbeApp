import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('Adding route on BE72:')
    run(c, 'ip route add 100.66.0.0/16 via 192.168.5.46 dev br-lan || true')
    print('Ping 100.66.1.1:')
    print(run(c, 'ping -c 2 -W 2 100.66.1.1 || true').decode())
    print('Testing nc to 100.66.1.1:54133:')
    print(run(c, 'nc -z -w 2 100.66.1.1 54133 && echo BE50_PORT_OPEN || echo BE50_PORT_CLOSED').decode())
    print('Testing curl http://100.66.1.1:')
    print(run(c, 'curl -s -m 2 -I http://100.66.1.1 || echo CURL_FAILED').decode())
finally:
    c.close()
