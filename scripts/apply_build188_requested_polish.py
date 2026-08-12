#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
ROUTER_SETTINGS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "app/build.gradle.kts"
ICON = ROOT / "app/src/main/res/drawable/labprobe_launcher_noglow.xml"


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
    new_pos = text.find(new, start)
    if new_pos >= 0:
        return text
    old_pos = text.find(old, start)
    if old_pos < 0:
        raise SystemExit(f"{label}: old form not found after marker")
    return text[:old_pos] + new + text[old_pos + len(old):]


# 1) Clean launcher artwork: drawn from scratch, so the old bitmap glow
# cannot survive. Same blue->mint radar/network composition.
ICON.parent.mkdir(parents=True, exist_ok=True)
ICON.write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="512dp"
    android:height="512dp"
    android:viewportWidth="512"
    android:viewportHeight="512">
    <path
        android:pathData="M92,16 H420 C462,16 496,50 496,92 V420 C496,462 462,496 420,496 H92 C50,496 16,462 16,420 V92 C16,50 50,16 92,16 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="48"
                android:startY="56"
                android:endX="470"
                android:endY="458"
                android:startColor="#1785B8"
                android:endColor="#2FD0B7" />
        </aapt:attr>
    </path>

    <path
        android:pathData="M256,78 A178,178 0,1 1,255.9 78"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#8ED9F1EF"
        android:strokeWidth="13"
        android:strokeLineCap="round" />
    <path
        android:pathData="M256,166 A90,90 0,1 1,255.9 166"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#38D9F1EF"
        android:strokeWidth="4" />
    <path
        android:pathData="M256,205 A51,51 0,1 1,255.9 205"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#2AD9F1EF"
        android:strokeWidth="3" />

    <path
        android:pathData="M158,135 L397,145 M158,135 L145,300 M145,300 L254,365 M254,365 L375,290 M375,290 L397,145 M255,252 L397,145 M255,252 L254,365"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#F5FCFF"
        android:strokeWidth="14"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />

    <path android:pathData="M158,112 A23,23 0,1 1,157.9 112 Z" android:fillColor="#F7FDFF" />
    <path android:pathData="M397,119 A26,26 0,1 1,396.9 119 Z" android:fillColor="#F7FDFF" />
    <path android:pathData="M255,226 A26,26 0,1 1,254.9 226 Z" android:fillColor="#F7FDFF" />
    <path android:pathData="M145,276 A24,24 0,1 1,144.9 276 Z" android:fillColor="#F7FDFF" />
    <path android:pathData="M375,266 A24,24 0,1 1,374.9 266 Z" android:fillColor="#F7FDFF" />
    <path android:pathData="M254,339 A26,26 0,1 1,253.9 339 Z" android:fillColor="#F7FDFF" />
</vector>
''', encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
manifest = manifest.replace('@drawable/labprobe_launcher_1024', '@drawable/labprobe_launcher_noglow')
MANIFEST.write_text(manifest, encoding="utf-8")

# 2) NAT "最近检测": +2dp only before first result row.
# Make the second line match 分析结果 Value typography.
native = NATIVE.read_text(encoding="utf-8")
native = replace_after(
    native,
    'NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)',
    'Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {',
    'Column(Modifier.fillMaxWidth().padding(top = if (index == 0) 6.dp else 4.dp, bottom = 4.dp)) {',
    "recent detection title gap",
)
native = replace_after(
    native,
    'NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)',
    '                    fontSize = LabTypography.Caption.fontSize,\n                    color = NativeMuted\n',
    '                    style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium)\n',
    "recent detection content typography",
)
NATIVE.write_text(native, encoding="utf-8")

# 3) Router settings: only boxed item titles one size smaller.
# Preserve CardTitle weight; only size/line height move down one token.
router = ROUTER_SETTINGS.read_text(encoding="utf-8")
router = replace_once(
    router,
    'Text(title, style = LabTypography.CardTitle.copy(color = SettingsInk))',
    '''Text(
                    title,
                    style = LabTypography.CardTitle.copy(
                        fontSize = LabTypography.SectionTitle.fontSize,
                        lineHeight = LabTypography.SectionTitle.lineHeight,
                        color = SettingsInk
                    )
                )''',
    "router connection title size",
)
router = replace_once(
    router,
    'Text(title, style = LabTypography.CardTitle.copy(color = if (enabled) SettingsInk else SettingsMuted))',
    '''Text(
                    title,
                    style = LabTypography.CardTitle.copy(
                        fontSize = LabTypography.SectionTitle.fontSize,
                        lineHeight = LabTypography.SectionTitle.lineHeight,
                        color = if (enabled) SettingsInk else SettingsMuted
                    )
                )''',
    "router tile title size",
)
ROUTER_SETTINGS.write_text(router, encoding="utf-8")

# 4) Records page: halve only inter-item/card spacing (3dp -> 1.5dp).
main = MAIN.read_text(encoding="utf-8")
main = replace_after(
    main,
    "fun EventsScreen(",
    "verticalArrangement = Arrangement.spacedBy(3.dp)",
    "verticalArrangement = Arrangement.spacedBy(1.5.dp)",
    "events list gap",
)
MAIN.write_text(main, encoding="utf-8")

# New APK installs over build187.
gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, "versionCode = 187", "versionCode = 188", "version code")
GRADLE.write_text(gradle, encoding="utf-8")

print("build188 requested-only polish applied")
