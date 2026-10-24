import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

print("Testing NAS SSH on 47.104.22.0:64186...")
try:
    c = connect('47.104.22.0', 64186, '18617143092', 'Tj19950115')
    print("NAS connected! Running uptime & docker ps:")
    print(run(c, 'uptime; docker ps --format "{{.Names}}: {{.Status}}"').decode())
    # Check nodebaby and ping to BE50 (100.66.1.1)
    print("Testing ping to BE50 (100.66.1.1) from NAS:")
    try:
        ping = run(c, 'ping -c 2 100.66.1.1 || true').decode()
        print(ping)
    except Exception as e:
        print("Ping error:", e)
    # Check BE50 curl from NAS
    print("Testing curl to BE50 eweb from NAS:")
    try:
        curl = run(c, 'curl -s -m 3 -I http://100.66.1.1 || true').decode()
        print(curl)
    except Exception as e:
        print("Curl error:", e)
    c.close()
except Exception as e:
    print("NAS SSH failed:", e)

print("\nTesting BE72 SSH on 47.104.22.0:64725...")
try:
    c = connect('47.104.22.0', 64725, 'root', 'Re238950')
    print("BE72 connected! dev_sta get -m sysinfo:")
    print(run(c, 'dev_sta get -m sysinfo').decode()[:200])
    c.close()
except Exception as e:
    print("BE72 SSH failed:", e)

print("\nTesting BE50 SSH on 47.104.22.0:64979...")
try:
    c = connect('47.104.22.0', 64979, 'root', 'Re127685')
    print("BE50 connected! dev_sta get -m sysinfo:")
    print(run(c, 'dev_sta get -m sysinfo').decode()[:200])
    c.close()
except Exception as e:
    print("BE50 SSH failed:", e)
