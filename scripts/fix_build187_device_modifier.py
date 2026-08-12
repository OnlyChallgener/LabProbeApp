#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
text = path.read_text(encoding="utf-8")
shadow_block = """        modifier = Modifier.shadow(
            4.dp,
            LabCoreSurface.CardShape,
            clip = false,
            ambientColor = LabV2.ShadowAmbient,
            spotColor = LabV2.ShadowSpot
        ),
"""
combined_old = """        modifier = Modifier.combinedClickable(onClick = onOpenDetails, onLongClick = onOpenDetails)
"""
combined_new = """        modifier = Modifier
            .shadow(
                4.dp,
                LabCoreSurface.CardShape,
                clip = false,
                ambientColor = LabV2.ShadowAmbient,
                spotColor = LabV2.ShadowSpot
            )
            .combinedClickable(onClick = onOpenDetails, onLongClick = onOpenDetails)
"""
marker = "fun DeviceSmartCard("
start = text.find(marker)
if start < 0:
    raise SystemExit("DeviceSmartCard not found")
end = text.find("\n@Composable\nfun DeviceSmartInfo", start)
if end < 0:
    raise SystemExit("DeviceSmartCard end marker not found")
block = text[start:end]
if shadow_block in block:
    block = block.replace(shadow_block, "", 1)
if combined_old in block:
    block = block.replace(combined_old, combined_new, 1)
elif combined_new not in block:
    raise SystemExit("DeviceSmartCard combinedClickable modifier not found")
text = text[:start] + block + text[end:]
path.write_text(text, encoding="utf-8")
print("build187 device modifier merged")
