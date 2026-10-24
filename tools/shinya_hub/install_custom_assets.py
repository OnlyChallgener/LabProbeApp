import shutil
from pathlib import Path
from PIL import Image

ROOT = Path("D:/Github/LabProbeApp")
ANALYSIS = ROOT / "test/_analysis/oct08-shinya-hub"
DECODED = ANALYSIS / "decoded"
CUSTOM = ROOT / "tools/shinya_hub/assets/custom_icons"

# 1. Target drawable dirs
mdpi = DECODED / "res/drawable-mdpi"
nodpi = DECODED / "res/drawable-nodpi"
mdpi.mkdir(parents=True, exist_ok=True)
nodpi.mkdir(parents=True, exist_ok=True)

# 2. Manufacturer icons (73x73)
ugreen_src = CUSTOM / "manufacturer_ugreen_73x73.png"
fnos_src = CUSTOM / "manufacturer_fnos_73x73.png"

shutil.copy2(ugreen_src, mdpi / "src_assets_image_manufacturer_ugreen.png")
shutil.copy2(fnos_src, mdpi / "src_assets_image_manufacturer_fnos.png")
print("Installed manufacturer icons into drawable-mdpi")

# 3. NAS device icons (120x120 for mdpi sta icons, 256x256 for nodpi)
nas_128 = CUSTOM / "device_nas_128x128.png"
# resize to exact 120x120 for sta icons
im_120 = Image.open(nas_128).resize((120, 120), Image.Resampling.LANCZOS)
im_120.save(mdpi / "src_assets_image_common_icon_statype_nas.png", "PNG")
im_120.save(mdpi / "src_assets_image_common_icon_child_statype_nas.png", "PNG")
print("Installed NAS sta icons (120x120) into drawable-mdpi")

# For drawable-nodpi, save high-res 256x256
nas_256 = CUSTOM / "device_nas_256x256.png"
shutil.copy2(nas_256, nodpi / "device_nas.png")
print("Installed device_nas.png (256x256) into drawable-nodpi")

# Also update assets/hub-appicons
hub_icons = ROOT / "tools/shinya_hub/assets/hub-appicons"
hub_icons.mkdir(parents=True, exist_ok=True)
shutil.copy2(nas_256, hub_icons / "device_nas.png")
shutil.copy2(ugreen_src, hub_icons / "ugreen.png")
shutil.copy2(fnos_src, hub_icons / "fnos.png")
print("Assets installed successfully.")
