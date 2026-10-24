"""Download public, unauthenticated original report page assets."""
import re
from pathlib import Path
import requests
out=Path('test/_analysis/oct12-game-reports');out.mkdir(exist_ok=True)
runtime=Path('test/_analysis/oct10-accounts/official-h5/script0.js').read_text()
session=requests.Session();session.trust_env=False
for chunk in [985,354,746]:
    for h in re.findall(r'(?:^|[,\{])'+str(chunk)+r':"([0-9a-f]+)"',runtime):
        for ext in ['js','css']:
            r=session.get(f'https://sy-oss-public.ruijie.com.cn/app-h5/static/{ext}/{chunk}.{h}.{ext}',timeout=25)
            if r.status_code==200 and not r.content.lstrip().startswith(b'<html'):
                (out/f'{chunk}.{h}.{ext}').write_bytes(r.content)
                print(chunk,ext,len(r.content),flush=True)
