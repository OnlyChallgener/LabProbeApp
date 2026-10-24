import sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'D:/Github/LabProbeApp/tools/shinya_hub')
from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())

sids = [19250, 19251, 19252, 19630, 19634, 19635, 19773, 19774, 19901, 19902, 19934, 19937, 19976, 19977, 20200, 20206, 20207, 20208, 20302, 20303, 20305, 20306, 20307, 20308, 20309]
for sid in sids:
    print(f'{sid}: {reader.strings[sid]}')
