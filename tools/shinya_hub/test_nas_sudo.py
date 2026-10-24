import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

nas = connect('100.66.1.4', 2122, '18617143092', 'Tj19950115')
try:
    print('SUCCESS! Connected to NAS!')
    print('whoami:', run(nas, 'whoami').decode())
    print('uptime:', run(nas, 'uptime').decode())
    print('docker ps:')
    print(run(nas, 'docker ps --format "{{.Names}}: {{.Status}} ({{.Image}})"', sudo_password='Tj19950115').decode())
finally:
    nas.close()
