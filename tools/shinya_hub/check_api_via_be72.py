import sys
sys.path.insert(0, r'D:\Github\labprobe-hub\scripts')
from eweb_hub_remote import connect, run

c = connect('47.104.22.0', 64725, 'root', 'Re238950')
try:
    print('Testing shinya-hub API from BE72:')
    out = run(c, 'curl -s -m 5 -k -X POST https://192.168.5.46:58544/api/v1/apMonitor/getProjectList2 -H "Content-Type: application/json" -d "{}"')
    print("getProjectList2:")
    print(out.decode())
finally:
    c.close()
