"""Package the inspected official channelView UI; replace only its two API calls."""
import concurrent.futures
import hashlib
import json
import re
import shutil
from pathlib import Path
from urllib.parse import urljoin

import requests

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'tools/shinya_hub/assets/hub-ui/scan-core'
CORE = ROOT / 'tools/shinya_hub/assets/hub-ui/floor-core'
SCAN = ROOT / 'test/_analysis/oct12-signal-tools/official-h5'
PUBLIC = 'https://sy-oss-public.ruijie.com.cn/app-h5/'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    # Report/support pages share this runtime. Retain their packaged resources
    # when refreshing the scan adaptation so build order cannot drop them.
    assets = json.loads((OUT / 'assets.json').read_text(encoding='utf8')) if (OUT / 'assets.json').exists() else {}
    for public, local in {
        'static/js/runtime.69f0505a.js': 'script0.js',
        'static/js/chunk-vantUI.aa1cfe2c.js': 'script1.js',
        'static/js/chunk-libs.3f5d2100.js': 'script2.js',
        'static/js/app.6df7475f.js': 'script3.js',
        **{'static/css/' + n: n for n in
           ('chunk-vantUI.545f1d25.css', 'chunk-libs.c7ed1a98.css', 'app.928b3c16.css')},
    }.items():
        shutil.copyfile(CORE / local, OUT / local)
        assets[PUBLIC + public] = local
    for name in ('680.096c2de6.js', '515.4f80f60c.js', '63.813881a8.js', '63.40c8d62e.css'):
        shutil.copyfile(SCAN / name, OUT / name)
        assets[PUBLIC + 'static/' + ('css/' if name.endswith('.css') else 'js/') + name] = name
    source = (SCAN / '63.813881a8.js').read_text(encoding='utf8')
    adapted = source
    for name, endpoint, action in (('b', 'getLastScan', 'read'), ('D', 'asyncChannelScan', 'start')):
        old = f'function {name}(t){{return o({{url:"/api/v1/device/state/{endpoint}",method:"post",data:t,hideloading:!0}})}}'
        assert adapted.count(old) == 1
        adapted = adapted.replace(old, f'function {name}(t){{return window.HubScanRequest("{action}")}}')
    # History entries are read-only on open. The vendor page starts a scan
    # automatically after missing history or a read error; keep its original
    # inactive/retry UI, with a new scan only from the user's refresh/button.
    start = adapted.index('getLastScanResult(){')
    end = adapted.index('},startScan(){', start)
    history = adapted[start:end]
    assert history.count('t.startScan()') == 3
    adapted = adapted[:start] + history.replace('t.startScan()', 't.scanning=!1') + adapted[end:]
    (OUT / '63.813881a8.js').write_text(adapted, encoding='utf8')

    # All visual assets used by this page are bundled, including unknown-brand
    # fallback. The WebView denies any request outside this exact URL map.
    shared = (CORE / 'script3.js').read_text(encoding='utf8')
    manufacturer_map = shared.split('$e={', 1)[1].split('},De=', 1)[0]
    brands = set(re.findall(r':"([^"]+)"', manufacturer_map))
    urls = {PUBLIC + 'image/refresh.png', PUBLIC + 'svga/xiaoxing-scan.svga',
            PUBLIC + 'fonts/iconfont.css',
            'https://shinya-public.oss-cn-hangzhou.aliyuncs.com/images/staType/router.png'}
    urls.update('https://shinya-public.oss-cn-hangzhou.aliyuncs.com/images/manufacturer/' + b + '.png' for b in brands)

    def fetch(url):
        # Encode Unicode/space path segments through requests' URL preparation.
        suffix = Path(url.split('?', 1)[0]).suffix or '.bin'
        name = hashlib.sha256(url.encode()).hexdigest()[:20] + suffix
        target = OUT / name
        if not target.exists():
            session = requests.Session()
            session.trust_env = False
            try:
                response = session.get(url, timeout=30)
                response.raise_for_status()
                target.write_bytes(response.content)
            finally:
                session.close()
        return url, name

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for url, name in pool.map(fetch, sorted(urls)):
            assets[url.split('?', 1)[0]] = name
    font_css = (OUT / assets[PUBLIC + 'fonts/iconfont.css']).read_text(encoding='utf8')
    font_urls = {urljoin(PUBLIC + 'fonts/iconfont.css', u.strip('"\''))
                 for u in re.findall(r'url\(([^)]+)\)', font_css) if not u.startswith('data:')}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for url, name in pool.map(fetch, sorted(font_urls)):
            assets[url.split('?', 1)[0]] = name
    (OUT / 'assets.json').write_text(json.dumps(assets, ensure_ascii=False, indent=2), encoding='utf8')
    (OUT / 'manifest.json').write_text(json.dumps({
        'page': 'official channelView', 'apiActions': ['read', 'start'],
        'originalScanSha256': hashlib.sha256((SCAN / '63.813881a8.js').read_bytes()).hexdigest(),
        'originalCssSha256': hashlib.sha256((SCAN / '63.40c8d62e.css').read_bytes()).hexdigest(),
        'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.iterdir() if p.is_file()},
    }, ensure_ascii=False, indent=2), encoding='utf8')
    print(f'Bundled official scan UI: {len(assets)} resources; original CSS unchanged')


if __name__ == '__main__':
    main()
