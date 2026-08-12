#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
text = path.read_text(encoding="utf-8")
old = """            }, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape, colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)) {
                Icon(Icons.Rounded.Sync, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(\"立即校准\", fontSize = 11.5.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }"""
new = """            }, modifier = Modifier.weight(1f).height(46.dp), shape = LabV2.ButtonShape, colors = ButtonDefaults.buttonColors(containerColor = settingsMint)) {
                Icon(Icons.Rounded.Sync, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(\"立即校准\", fontSize = 11.5.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }"""
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("settings calibrate button block not found")
path.write_text(text, encoding="utf-8")
print("build187 calibrate button mint fix applied")
