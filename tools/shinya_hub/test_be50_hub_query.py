import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 63829, 'ruijie', '123456')
try:
    token = 'c3db47609dd11465778d15af80ecc77ad82f1efb19ea2402bb09e4a221908a8'
    print('Testing query to getProjectList2 from BE50 to NAS 100.66.1.4:58443:')
    out = run(c, f'curl -s -X POST -H "Authorization: Bearer {token}" -H "Content-Type: application/json" http://100.66.1.4:58443/homewlan/apMonitor/getProjectList2 -d "{{}}"').decode()
    print(out)
finally:
    c.close()
