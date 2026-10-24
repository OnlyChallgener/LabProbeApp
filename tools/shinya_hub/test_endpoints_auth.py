import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

print("=== Testing NAS 47.104.155.247:63029 ===")
for u in ['root', 'admin', '18617143092', 'Demon']:
    for p in ['Re238950', 'Rj127685', 'Tj19950115.', 'Tj19950115']:
        try:
            c = connect('47.104.155.247', 63029, u, p)
            print(f"SUCCESS NAS! User={u}, Pass={p}")
            print(run(c, 'whoami; uptime').decode())
            c.close()
            break
        except Exception:
            pass

print("=== Testing BE50 47.104.22.0:63829 ===")
for u in ['root', 'admin']:
    for p in ['Re238950', 'Rj127685', 'Tj19950115.', 'Tj19950115']:
        try:
            c = connect('47.104.22.0', 63829, u, p)
            print(f"SUCCESS BE50! User={u}, Pass={p}")
            print(run(c, 'dev_sta get -m sysinfo').decode())
            c.close()
            break
        except Exception:
            pass
