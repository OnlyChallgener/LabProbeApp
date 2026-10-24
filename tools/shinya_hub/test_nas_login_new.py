import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

gateway = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('Opening channel to NAS 192.168.5.46:2122...')
    for p in ['Tj19950115', 'Tj19950115.']:
        try:
            chan = gateway.get_transport().open_channel('direct-tcpip', ('192.168.5.46', 2122), ('127.0.0.1', 0), timeout=8)
            nas = connect('192.168.5.46', 2122, '18617143092', p, sock=chan)
            print(f'SUCCESS! Connected to NAS! Pass={p}')
            print(run(nas, 'whoami; uptime; docker ps --format "{{.Names}}: {{.Status}}"').decode())
            nas.close()
            break
        except Exception as e:
            print(f'Failed with {p}: {e}')
finally:
    gateway.close()
