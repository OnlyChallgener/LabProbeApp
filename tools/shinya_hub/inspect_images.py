import os
from PIL import Image
from pathlib import Path

img1_path = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529518884.png")
img2_path = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529518886.png")
img3_path = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529575781.png")

for i, p in enumerate([img1_path, img2_path, img3_path], 1):
    im = Image.open(p)
    print(f"Image {i} ({p.name}): size={im.size}, mode={im.mode}, format={im.format}")

# Also check existing manufacturer images in decoded APK
decoded_res = Path(r"D:/Github/LabProbeApp/test/_analysis/oct08-shinya-hub/decoded/res")
manufacturer_files = list(decoded_res.glob("**/manufacturer*")) + list(decoded_res.glob("**/apple*")) + list(decoded_res.glob("**/huawei*"))
print(f"\nFound {len(manufacturer_files)} sample manufacturer assets in res:")
for f in manufacturer_files[:10]:
    im = Image.open(f)
    print(f"  {f.relative_to(decoded_res)}: size={im.size}, mode={im.mode}")

# Also check device icons in res
device_files = list(decoded_res.glob("**/device_*")) + list(decoded_res.glob("**/sta_*"))
print(f"\nFound {len(device_files)} sample device assets in res:")
for f in device_files[:10]:
    im = Image.open(f)
    print(f"  {f.relative_to(decoded_res)}: size={im.size}, mode={im.mode}")
