import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('Testing 58544 with /experienceVisual/getSignalCover:')
    out1 = run(c, 'curl -k -s -i -X POST https://192.168.5.46:58544/experienceVisual/getSignalCover -H "Content-Type: application/json" -d "{}"')
    print("58544 /experienceVisual/getSignalCover:")
    print(out1.decode()[:400])

    print('Testing 58544 with /api/v1/experienceVisual/getSignalCover:')
    out2 = run(c, 'curl -k -s -i -X POST https://192.168.5.46:58544/api/v1/experienceVisual/getSignalCover -H "Content-Type: application/json" -d "{}"')
    print("58544 /api/v1/experienceVisual/getSignalCover:")
    print(out2.decode()[:400])
finally:
    c.close()
