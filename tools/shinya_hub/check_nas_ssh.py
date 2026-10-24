import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

gateway = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    for port in [2122, 22, 80, 58544]:
        try:
            channel = gateway.get_transport().open_channel('direct-tcpip', ('192.168.5.46', port), ('127.0.0.1', 0), timeout=2)
            print(f"Port {port} on 192.168.5.46: OPEN!")
            channel.close()
        except Exception as e:
            print(f"Port {port} on 192.168.5.46: FAILED ({e})")
finally:
    gateway.close()
