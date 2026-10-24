import os
from PIL import Image
from pathlib import Path

out_dir = Path("D:/Github/LabProbeApp/tools/shinya_hub/assets/custom_icons")
out_dir.mkdir(parents=True, exist_ok=True)

img_ugreen = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529518884.png")
img_fnos = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529518886.png")
img_nas = Path(r"C:/Users/admin/.gemini/antigravity/brain/0f0e78d4-9c76-4b6d-87e3-5d19bbc7aea8/.user_uploaded/media_1791529575781.png")

def process_square_icon(src_path, out_base_name, target_size=(128, 128), padding_ratio=0.1):
    im = Image.open(src_path).convert("RGBA")
    bbox = im.getbbox()
    if bbox:
        cropped = im.crop(bbox)
    else:
        cropped = im
    w, h = cropped.size
    max_side = max(w, h)
    
    # Calculate target box with padding
    pad = int(max_side * padding_ratio)
    canvas_size = max_side + 2 * pad
    square = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    paste_x = (canvas_size - w) // 2
    paste_y = (canvas_size - h) // 2
    square.paste(cropped, (paste_x, paste_y), cropped)
    
    # Save standard sizes
    for sz in [73, 128, 256]:
        resized = square.resize((sz, sz), Image.Resampling.LANCZOS)
        out_file = out_dir / f"{out_base_name}_{sz}x{sz}.png"
        resized.save(out_file, "PNG")
        print(f"Saved {out_file} ({sz}x{sz})")
    
    # Also save standard 73x73 as the drop-in asset
    square.resize((73, 73), Image.Resampling.LANCZOS).save(out_dir / f"{out_base_name}.png", "PNG")

process_square_icon(img_ugreen, "manufacturer_ugreen")
process_square_icon(img_fnos, "manufacturer_fnos")
process_square_icon(img_nas, "device_nas")
print("All custom icons rendered successfully.")
