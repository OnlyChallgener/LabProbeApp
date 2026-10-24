import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print("=== BE72 Network & Neighbor info ===")
    print("1. ip addr / ifconfig:")
    print(run(c, 'ip addr').decode())
    print("2. arp -n:")
    print(run(c, 'arp -n').decode())
    print("3. ping NAS (192.168.5.46):")
    print(run(c, 'ping -c 2 192.168.5.46 || true').decode())
    print("4. ubus call network.interface dump (summary):")
    print(run(c, "ubus call network.interface dump").decode()[:1000])
finally:
    c.close()
