import os
from PIL import Image
from pathlib import Path

out_dir = Path("D:/Github/LabProbeApp/tools/shinya_hub/assets/custom_icons")
out_dir.mkdir(parents=True, exist_ok=True)

img_ugreen = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529518884.png")
img_fnos = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529518886.png")
img_nas = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529575781.png")

# 1. Inspect image properties and transparency
for name, p in [("ugreen", img_ugreen), ("fnos", img_fnos), ("nas", img_nas)]:
    im = Image.open(p)
    bbox = im.getbbox()
    print(f"{name}: size={im.size}, mode={im.mode}, bbox={bbox}")
