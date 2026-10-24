import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 63829, 'ruijie', '123456')
try:
    print('=== crontab ===')
    print(run(c, 'crontab -l 2>/dev/null || true').decode())
    print('=== cron dirs ===')
    print(run(c, 'ls -la /etc/cron* 2>/dev/null || true').decode())
    print('=== shinya files ===')
    print(run(c, 'find /tmp /etc /usr -name "*shinya*" 2>/dev/null || true').decode())
    print('=== running processes matching lua/mqtt ===')
    print(run(c, 'ps w | grep -E "lua|mqtt" || true').decode())
finally:
    c.close()
