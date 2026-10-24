import requests, json

headers = {'type': 'shinyaApp', 'Accept': 'application/json', 'Content-Type': 'application/json'}
data = {'account': '18617143092', 'password': 'Tj19950115.'}
r = requests.post('https://shinyaapp.ruijie.com.cn/api/v1/base/homeUser/accountPasswordLogin', headers=headers, json=data, timeout=10)
res = r.json()
if res.get('code') == 0:
    token = res['data']['token']
    headers['Authorization'] = f'Bearer {token}'
    r2 = requests.post('https://shinyaapp.ruijie.com.cn/homewlan/apMonitor/getProjectList2', headers=headers, json={}, timeout=10)
    for p in r2.json().get('data', {}).get('dataList', []):
        pid = p.get('buildingId')
        pname = p.get('projectName')
        print(f"Project {pname} (ID: {pid}): online={p.get('online')}")
        r3 = requests.post('https://shinyaapp.ruijie.com.cn/homewlan/homeAp/getDeviceInfoList2', headers=headers, json={'projectId': pid}, timeout=10)
        aps = r3.json().get('data', {}).get('apList', [])
        for ap in aps:
            print(f"  AP {ap.get('model')} ({ap.get('sn')}): isOnline={ap.get('isOnline')} lastSeen={ap.get('lastSeenTime')} wanIp={ap.get('wanIp')}")
else:
    print('Official login failed:', res)
