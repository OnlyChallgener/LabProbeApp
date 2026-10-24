from pathlib import Path
from PIL import Image

decoded = Path(r"D:/Github/LabProbeApp/test/_analysis/oct08-shinya-hub/decoded")
all_pngs = list(decoded.rglob("*.png"))
print(f"Total PNGs in decoded: {len(all_pngs)}")

# Look for apple, huawei, or manufacturer
for p in all_pngs:
    p_lower = str(p).lower()
    if any(k in p_lower for k in ['apple', 'huawei', 'nas', 'router', 'sta', 'manufacture']):
        im = Image.open(p)
        print(f"  {p.relative_to(decoded)}: size={im.size}, mode={im.mode}")
