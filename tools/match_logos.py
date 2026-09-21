"""Match the user's logo drop against the generated RDPI catalog.

Prints, per source file: the catalog app it can drive (exact name, or a normalised
hit like 酷安 -> 酷安应用市场), and whether we already ship art for that app.
Unmatched names are listed separately -- those are logos for apps the router cannot
classify, so an icon would be a lie about coverage.
"""
import io
import os
import re
import sys

from PIL import Image

ROOT = r"D:\Github\LabProbeApp"
LOGO = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "test", "应用logo")
ART = os.path.join(ROOT, "app", "src", "main", "assets", "appicons")
CAT = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "labprobe", "app", "RdpiCatalogGenerated.kt")

# Names the vendor DB uses but the user's file spells differently (or one file that
# stands for several apps). Kept explicit so a rename never silently mislabels an app.
ALIAS = {
    "酷安": ["酷安应用市场"],
    "WPSOffice": ["WPS Office"],
    "12306": ["铁路12306"],
    "番茄小说": ["番茄免费小说"],
    "YY语音": ["YY"],
    "腾讯微视": ["微视"],
}

catalog = [m.group(1) for m in re.finditer(r'RdpiCatalogEntry\("([^"]+)"', io.open(CAT, encoding="utf-8").read())]
have_art = {os.path.splitext(f)[0] for f in os.listdir(ART)}
icon_key_src = io.open(os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "labprobe", "app", "ChildInternetRepository.kt"), encoding="utf-8").read()
mapped = {m.group(1) for m in re.finditer(r'"([^"]+)" -> "[a-z0-9-]+"', icon_key_src.split("private fun dashboardIconKey")[1])}

names = {c for c in catalog}
rows = []
for fn in sorted(os.listdir(LOGO)):
    if not fn.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
        continue
    stem = os.path.splitext(fn)[0]
    parts = [p for p in re.split(r"[#]", stem) if p]
    targets = []
    for p in parts:
        cands = ALIAS.get(p, [p]) + [p + "系列", p + "应用商店", p + "市场"]
        hit = [c for c in cands if c in names]
        targets.append((p, hit[0] if hit else None))
    im = Image.open(os.path.join(LOGO, fn))
    rows.append((fn, im.size, targets))

print(f"catalog={len(catalog)} files={len(rows)}\n")
unmatched = []
for fn, size, targets in rows:
    for part, hit in targets:
        if hit:
            art = "art-ok" if hit in mapped else "NEEDS-MAP"
            print(f"{fn:<28} {str(size):<12} -> {hit:<12} {art}")
        else:
            unmatched.append((fn, part))
print("\n-- no catalog app with that name --")
print("、".join(sorted({p for _, p in unmatched})))
