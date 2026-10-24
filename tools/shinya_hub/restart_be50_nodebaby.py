import sys, time
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 63829, 'ruijie', '123456')
try:
    print('Stopping nodebaby on BE50...')
    print(run(c, '/etc/init.d/owjdxb stop').decode())
    time.sleep(3)
    print('Starting nodebaby on BE50...')
    print(run(c, '/etc/init.d/owjdxb start').decode())
    time.sleep(3)
    print('Process list:')
    print(run(c, 'ps w | grep -iE "[N]odeBaby|[o]wjdxb"').decode())
    print('Testing ping to NAS 100.66.1.4:')
    print(run(c, 'ping -c 2 100.66.1.4').decode())
finally:
    c.close()
