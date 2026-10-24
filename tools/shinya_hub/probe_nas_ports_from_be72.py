import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('=== BE72 probing NAS ports with nc ===')
    res = run(c, "for p in 21 22 80 443 2122 5000 5001 5666 5678 8000 8080 58443 58544 5667 9000 9443; do nc -z -w 1 192.168.5.46 $p && echo Port $p OPEN || true; done")
    print(res.decode())
finally:
    c.close()
