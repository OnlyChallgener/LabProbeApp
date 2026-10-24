import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

combos = [
    ('ruijie', '123456'),
    ('root', '123456'),
    ('admin', '123456'),
    ('root', 'Re238950'),
    ('root', 'Tj19950115.'),
    ('root', 'Tj19950115'),
    ('18617143092', 'Tj19950115.'),
    ('18617143092', 'Tj19950115'),
    ('admin', 'Tj19950115.'),
    ('admin', 'Tj19950115'),
    ('Demon', 'Tj19950115.'),
    ('Demon', 'Tj19950115'),
]

for u, p in combos:
    try:
        c = connect('47.104.155.247', 63029, u, p)
        print(f'SUCCESS! NAS: user={u}, pass={p}')
        print(run(c, 'whoami; uptime; docker ps --format "{{.Names}}: {{.Status}}"').decode())
        c.close()
        break
    except Exception as e:
        print(f'Failed {u}/{p}: {e}')
