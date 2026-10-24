import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    token = 'c3db47609dd11465778d15af80ecc77ad82f1efb19ea2402bb09e4a221908a8'
    print('Testing query to http://192.168.5.46:58443/api/router/devices with Bearer token:')
    out = run(c, f'curl -s -H "Authorization: Bearer {token}" http://192.168.5.46:58443/api/router/devices')
    print(out.decode()[:500])
    print('Testing query to getProjectList2:')
    out2 = run(c, f'curl -s -X POST -H "Authorization: Bearer {token}" -H "Content-Type: application/json" http://192.168.5.46:58443/homewlan/apMonitor/getProjectList2 -d "{{}}"')
    print(out2.decode()[:500])
    print('Testing query to getDeviceInfoList2:')
    out3 = run(c, f'curl -s -X POST -H "Authorization: Bearer {token}" -H "Content-Type: application/json" http://192.168.5.46:58443/homewlan/homeAp/getDeviceInfoList2 -d "{{\\"projectId\\":\\"18850348\\"}}"')
    print(out3.decode()[:500])
finally:
    c.close()
