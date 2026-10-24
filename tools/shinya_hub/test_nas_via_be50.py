import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

gateway = connect('47.104.22.0', 63829, 'ruijie', '123456')
try:
    print('Connected to BE50! Testing connection to NAS 100.66.1.4...')
    for port in [2122, 22]:
        for p in ['Tj19950115', 'Tj19950115.']:
            try:
                chan = gateway.get_transport().open_channel('direct-tcpip', ('100.66.1.4', port), ('127.0.0.1', 0), timeout=8)
                nas = connect('100.66.1.4', port, '18617143092', p, sock=chan)
                print(f'SUCCESS! Connected to NAS via BE50 nodebaby on port {port}! Pass={p}')
                print(run(nas, 'whoami; uptime; docker ps --format "{{.Names}}: {{.Status}}"').decode())
                nas.close()
                break
            except Exception as e:
                print(f'Port {port} pass {p} failed: {e}')
finally:
    gateway.close()
