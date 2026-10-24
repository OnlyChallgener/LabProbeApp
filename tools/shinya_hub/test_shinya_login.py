import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('Testing login to https://192.168.5.46:58544/api/v1/hub/account/login:')
    combos = [
        ('18617143092', 'Tj19950115.'),
        ('18617143092', 'Tj19950115'),
        ('Demon', 'Tj19950115.'),
        ('Demon', 'Tj19950115'),
        ('admin', 'admin'),
        ('admin', '12345678'),
        ('Demon', 'Re238950'),
        ('admin', 'Re238950'),
    ]
    for u, p in combos:
        cmd = f'curl -s -k -X POST https://192.168.5.46:58544/api/v1/hub/account/login -H "Content-Type: application/json" -d \'{{"account":"{u}","password":"{p}"}}\''
        out = run(c, cmd).decode()
        if '"code":0' in out:
            print(f"SUCCESS: user={u}, pass={p}")
            print(out)
            break
        else:
            print(f"Failed {u}/{p}: {out.strip()[:100]}")
finally:
    c.close()
