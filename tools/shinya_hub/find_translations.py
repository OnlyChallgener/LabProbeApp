from pathlib import Path
from patch_bundle import load

ROOT = Path('D:/Github/LabProbeApp')
ANALYSIS = ROOT / 'test/_analysis/oct08-shinya-hub'
bundle_path = ANALYSIS / 'index.android.bundle'
reader = load(bundle_path.read_bytes())

print("=== Searching staType. strings ===")
for sid, s in enumerate(reader.strings):
    if 'staType.' in s:
        print(f"SID {sid}: {repr(s)}")

print("\n=== Searching manufacture. strings ===")
for sid, s in enumerate(reader.strings):
    if 'manufacture.' in s:
        print(f"SID {sid}: {repr(s)}")
