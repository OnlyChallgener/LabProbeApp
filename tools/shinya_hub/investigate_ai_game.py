"""Read the authorized BE72 game contracts and compare the installed official APK."""
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path

from audit_game_environment import clean
from remote_transport import connect, run

OUT = Path('test/_analysis/oct13-official-game')


def firmware():
    client = connect(os.environ['SHINYA_BE72_HOST'], int(os.environ['SHINYA_BE72_PORT']),
                     os.environ['SHINYA_BE72_USER'], os.environ['SHINYA_BE72_PASSWORD'])
    result = {}
    try:
        identity = json.loads(run(client, 'dev_sta get -m sysinfo'))
        assert identity.get('serial_num') == 'G1TD8RX039025', 'Unexpected router identity'
        print('BE72 identity confirmed', flush=True)
        for command in [
            'dev_sta get -m rcgame',
            'dev_sta get -m rcgame \'{"func":"getGameInfo"}\'',
            'dev_config get -m rcgame',
            'dev_config get -m flowctrl',
            'dev_config get -m flowctrl_qos',
            'ac_config get -m lowRate',
            'dev_sta get -m lowRate',
            "ubus call rcgame status '{}'",
            "ubus call rcgame flow '{}'",
            "ubus call rcgame air_user '{}'",
            'ubus -v list low_rate',
            'cat /etc/rg_config/single/low_rate_start.json',
            'cat /tmp/log/rcgame.log',
            'dev_cap get -m gateway',
            'dev_cap get -m eweb',
            r"find /usr/local/lua -maxdepth 2 -type f | sed -n '/[Gg]ame\|[Qq]os\|lowRate\|[Ss]niffer/p'",
            'dev_sta get -m chanIntfc',
            'uci show rcgame',
            'ubus -v list rcgame',
            'ls -l /tmp/log/rcgame*',
            'ls /usr/local/lua/dev_sta | sed -n /game/p',
            'ls /usr/local/lua/dev_config | sed -n /game/p',
        ]:
            try:
                raw = run(client, command, timeout=25).decode(errors='replace')
                try:
                    value = clean(json.loads(raw))
                except ValueError:
                    value = raw
                result[command] = value
                print(command, 'saved', flush=True)
            except Exception as error:
                result[command] = {'error': type(error).__name__}
                print(command, type(error).__name__, flush=True)
        for path in ['/usr/local/lua/dev_config/rcgame.lua', '/usr/local/lua/dev_sta/rcgame.lua',
                     '/usr/local/lua/dev_sta/gameInfo.lua', '/etc/init.d/rcgame',
                     '/usr/local/lua/ac_config/lowRate.lua', '/usr/local/lua/dev_config/flowctrl.lua']:
            try:
                name = path.replace('/', '_').lstrip('_')
                (OUT / name).write_bytes(run(client, 'cat ' + path))
            except Exception:
                pass
    finally:
        client.close()
    (OUT / 'be72-reads.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')


def apk():
    with zipfile.ZipFile(OUT / 'official-installed.apk') as source:
        bundle = source.read('assets/index.android.bundle')
    (OUT / 'official.bundle').write_bytes(bundle)
    original = Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
    print('Installed bundle matches previously inspected official bundle:', bundle == original)
    print('Installed bundle SHA256:', hashlib.sha256(bundle).hexdigest())
    functions = json.loads(Path('test/_analysis/oct08-shinya-hub/feature-functions.json').read_text(encoding='utf8'))
    keywords = re.compile(r'AI降噪|AI流控|AI防抖|getGameStatistics|getGameLog|FlowCtrl|flowCtrl|gameJitter|gameNoise|antiJitter|防节电|特殊终端|GameAccelerateEngine|游戏加速引擎|getAi|setAi|gameAI|AiNoise|gameAi')
    matches = [{**row, 'strings': [s for s in row.get('strings', []) if keywords.search(s)]}
               for row in functions if any(keywords.search(s) for s in row.get('strings', []))]
    (OUT / 'game-functions.json').write_text(json.dumps(matches, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(matches, ensure_ascii=False))
    logs = (OUT / 'logcat-raw.txt').read_text(encoding='utf8', errors='replace')
    print('Logcat API paths:', sorted(set(re.findall(r'/(?:api/|mvp/|eSportAccelerate/)[A-Za-z0-9_/]+', logs))))


def cloud():
    import requests
    sys.path.insert(0, 'D:/Github/ShinyaHub')
    from shinya_hub.vault import ConfigVault
    saved = ConfigVault(Path('test/_keys/shinya-official-account')).load()
    print('Existing authorized session fields:', sorted(saved))
    if not saved.get('token'):
        raise RuntimeError('No saved authorized session')
    session = requests.Session()
    session.trust_env = False
    headers = {'Authorization': 'Bearer '+saved['token'], 'type': 'shinyaApp', 'projectId': '18850348'}
    result = {}
    for path, payload in [
        ('/mvp/homewlan/apMonitor/getProjectList2', {}),
        ('/mvp/homewlan/eSportAccelerate/getEsportAccelerateStatus', {
            'projectId': 18850348, 'isGetRcgameStatus': False, 'isGetNoiseReductionStatus': False,
            'isGetLowRateStatus': False, 'isGetFlowCtrlQosStatus': True, 'isGetFlowCtrlStatus': True,
            'isGetPowerEconomize': False, 'isGetLowRateMode': False}),
        ('/api/v1/game/accelerate/getGameStatistics', {'sn': 'G1TD8RX039025', 'projectId':'18850348'}),
        ('/api/v1/game/accelerate/getGameLog', {'sn': 'G1TD8RX039025'}),
        ('/api/v1/game/accelerate/getAccelerateStatistics', {'sn': 'G1TD8RX039025', 'projectId':'18850348'}),
    ]:
        response = session.post('https://shinyaapp.ruijie.com.cn'+path, json=payload,
                                headers=headers, timeout=20, allow_redirects=False)
        try:
            value = clean(response.json())
        except ValueError:
            value = {'httpStatus': response.status_code}
        if path.endswith('getProjectList2') and isinstance(value.get('data'),dict):
            projects=value['data'].get('dataList',[])
            value['data']['dataList']=[p for p in projects if p.get('mainApSn')=='G1TD8RX039025'
                or any(a.get('sn')=='G1TD8RX039025' for a in p.get('apList',[]))]
        result[path] = value
        print(path, json.dumps(value, ensure_ascii=False)[:2000], flush=True)
    (OUT/'official-cloud-reads.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    {'apk': apk, 'firmware': firmware, 'cloud': cloud}[sys.argv[1]]()
