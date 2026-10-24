"""Combine coverage and router tools in one guarded backend deployment."""
import shutil
from pathlib import Path
game=Path('test/_analysis/oct12-game-batch/fixes/deployment')
coverage=Path('test/_analysis/oct12-signal-coverage')
router=Path('test/_analysis/oct12-router-tools')
out=Path('test/_analysis/oct12-controls');files=out/'deployment';base=out/'baseline'
files.mkdir(parents=True,exist_ok=True);base.mkdir(exist_ok=True)
assert (coverage/'baseline/api.py').read_bytes()==(router/'baseline/api.py').read_bytes()
for origin in (coverage,router):
    for p in (origin/'deployment').iterdir():
        if p.is_file():shutil.copyfile(p,files/p.name)
    for p in (origin/'baseline').iterdir():
        if p.is_file():
            if (base/p.name).exists():assert (base/p.name).read_bytes()==p.read_bytes()
            shutil.copyfile(p,base/p.name)
s=(files/'api.py').read_text(encoding='utf8')
old="online_wireless = [s for s in stations if s.get('online') and s.get('wifiBand') != 'wire']"
new="online_wireless = [s for s in stations if s.get('isTodayOnline') and s.get('wifiBand') != 'wire']"
assert s.count(old)==1
(files/'api.py').write_text(s.replace(old,new),encoding='utf8')
shutil.copyfile('tools/shinya_hub/router_tools_bridge_source.py',files/'router_tools_bridge.py')
for p in files.glob('*.py'):compile(p.read_text(encoding='utf8'),str(p),'exec')
print('Combined scoped deployment prepared')
