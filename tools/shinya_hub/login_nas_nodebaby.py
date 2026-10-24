import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

for p in ['Tj19950115', 'Tj19950115.']:
    try:
        nas = connect('100.66.1.4', 2122, '18617143092', p)
        print(f'SUCCESS! Connected to NAS! Pass={p}')
        print('whoami, uptime, docker ps:')
        print(run(nas, 'whoami; uptime; docker ps --format "{{.Names}}: {{.Status}}"').decode())
        nas.close()
        break
    except Exception as e:
        print(f'Pass {p} failed: {e}')
