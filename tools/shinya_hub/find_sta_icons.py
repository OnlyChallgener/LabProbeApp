from pathlib import Path
from PIL import Image

mdpi = Path(r"D:/Github/LabProbeApp/test/_analysis/oct08-shinya-hub/decoded/res/drawable-mdpi")
for p in sorted(mdpi.glob("src_assets_image_*")):
    name = p.name
    if any(k in name for k in ['sta', 'device', 'nas', 'other', 'pc', 'pad', 'phone', 'router', 'type']):
        im = Image.open(p)
        print(f"  {p.name}: size={im.size}, mode={im.mode}")
