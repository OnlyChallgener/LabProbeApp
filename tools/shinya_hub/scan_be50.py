import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('Scanning BE50 (100.66.1.1) ports from BE72:')
    res = run(c, "for p in 22 23 80 443 3844 54133 8080 8443; do nc -z -w 1 100.66.1.1 $p && echo Port $p OPEN || true; done")
    print(res.decode())
    # Also check if there's any response banner on open ports
    for p in [22, 54133, 80]:
        print(f"Banner on port {p}:")
        print(run(c, f"nc -w 2 100.66.1.1 {p} || true").decode())
finally:
    c.close()
