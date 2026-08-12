#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
ICON = ROOT / "app/src/main/res/drawable/labprobe_launcher_noglow.xml"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old form, found {count}")
    return text.replace(old, new, 1)


def replace_after(text: str, marker: str, old: str, new: str, label: str) -> str:
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"{label}: marker not found")
    if text.find(new, start) >= 0:
        return text
    pos = text.find(old, start)
    if pos < 0:
        raise SystemExit(f"{label}: old form not found after marker")
    return text[:pos] + new + text[pos + len(old):]


# 1) Launcher: keep the center network/radar artwork exactly unchanged.
# Only extend the existing blue->mint rounded background to the viewport edges.
icon = ICON.read_text(encoding="utf-8")
icon = replace_once(
    icon,
    'android:pathData="M92,16 H420 C462,16 496,50 496,92 V420 C496,462 462,496 420,496 H92 C50,496 16,462 16,420 V92 C16,50 50,16 92,16 Z"',
    'android:pathData="M76,0 H436 C478,0 512,34 512,76 V436 C512,478 478,512 436,512 H76 C34,512 0,478 0,436 V76 C0,34 34,0 76,0 Z"',
    "launcher background fill",
)
ICON.write_text(icon, encoding="utf-8")

# 2) Settings buttons: match the Beta upgrade button color exactly (LabV2.Cyan = #10A9C8).
main = MAIN.read_text(encoding="utf-8")
main = replace_once(
    main,
    'settingsMint = Color(0xFF28BFA3)',
    'settingsMint = Color(0xFF10A9C8)',
    "settings button color",
)

# 4) Records event-card spacing: halve 1.5dp again, only inside EventsScreen.
main = replace_after(
    main,
    'fun EventsScreen(',
    'verticalArrangement = Arrangement.spacedBy(1.5.dp)',
    'verticalArrangement = Arrangement.spacedBy(0.75.dp)',
    "event card spacing",
)
MAIN.write_text(main, encoding="utf-8")

# 3) NAT analysis + recent detection body use the same monospace family as detection log.
# Font sizes/line heights are untouched. Recent detection gets only a 2dp title/content gap.
native = NATIVE.read_text(encoding="utf-8")
analysis_marker = 'private fun NativeAnalysisValueRow(label: String, value: String)'
native = replace_after(
    native,
    analysis_marker,
    'style = LabTypography.Value.copy(color = NativeInk, fontWeight = FontWeight.Bold)',
    'style = LabTypography.Value.copy(color = NativeInk, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)',
    "analysis label monospace",
)
native = replace_after(
    native,
    analysis_marker,
    'style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium),',
    'style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),',
    "analysis value monospace",
)
history_marker = 'NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)'
native = replace_after(
    native,
    history_marker,
    'Column(Modifier.fillMaxWidth().padding(top = if (index == 0) 6.dp else 4.dp, bottom = 4.dp)) {',
    'Column(Modifier.fillMaxWidth().padding(top = if (index == 0) 6.dp else 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {',
    "recent detection title body gap",
)
native = replace_after(
    native,
    history_marker,
    'fontWeight = FontWeight.Bold,\n                            color = NativeInk,',
    'fontWeight = FontWeight.Bold,\n                            fontFamily = FontFamily.Monospace,\n                            color = NativeInk,',
    "recent detection title monospace",
)
native = replace_after(
    native,
    history_marker,
    'style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium)',
    'style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)',
    "recent detection body monospace",
)
NATIVE.write_text(native, encoding="utf-8")

# New installable build over build188.
gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, 'versionCode = 188', 'versionCode = 189', "version code")
GRADLE.write_text(gradle, encoding="utf-8")

print("build189 requested-only polish applied")
