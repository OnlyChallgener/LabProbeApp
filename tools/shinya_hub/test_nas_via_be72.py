import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

gateway = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('BE72 connected. Opening direct-tcpip channel to 192.168.5.46:2122...')
    channel = gateway.get_transport().open_channel('direct-tcpip', ('192.168.5.46', 2122), ('127.0.0.1', 0), timeout=8)
    print('Channel opened! Connecting to NAS SSH via channel...')
    nas = connect('192.168.5.46', 2122, '18617143092', 'Tj19950115', sock=channel)
    print('SUCCESS! Connected to NAS via BE72 tunnel!')
    print(run(nas, 'uptime; docker ps --format "{{.Names}}: {{.Status}}"').decode())
    
    # Check nodebaby (节点小宝) status on NAS
    print("=== Checking nodebaby container / route to BE50 ===")
    print("ip route:")
    print(run(nas, 'ip route').decode())
    print("Ping to BE50 100.66.1.1:")
    print(run(nas, 'ping -c 2 100.66.1.1 || true').decode())
    print("Curl BE50 http://100.66.1.1:")
    print(run(nas, 'curl -s -m 3 -I http://100.66.1.1 || true').decode())

    # Check ShinyaHub container status and logs
    print("=== Checking shinya-hub container ===")
    print(run(nas, 'docker ps | grep -E "shinya|labprobe" || true').decode())
    nas.close()
finally:
    gateway.close()
