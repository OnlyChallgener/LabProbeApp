from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def p(name: str) -> Path:
    return ROOT / name


def read(name: str) -> str:
    return p(name).read_text(encoding="utf-8")


def write(name: str, text: str) -> None:
    p(name).write_text(text, encoding="utf-8")


def ensure_replace(name: str, old: str, new: str) -> None:
    text = read(name)
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"{name}: neither old nor final text found: {old[:100]!r}")
    write(name, text.replace(old, new, 1))


def require(name: str, needle: str) -> None:
    if needle not in read(name):
        raise RuntimeError(f"{name}: required final marker missing: {needle!r}")


# v0.10.42 / build184. Already-applied source is accepted.
gradle = "app/build.gradle.kts"
ensure_replace(
    gradle,
    'versionCode = 181\n        versionName = "0.10.39"',
    'versionCode = 184\n        versionName = "0.10.42"',
)

# WOL title smaller, not thinner.
wol = "app/src/main/kotlin/com/labprobe/app/WolManagementPanel.kt"
ensure_replace(
    wol,
    'Text("WOL 设备", fontSize = 16.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)',
    'Text("WOL 设备", fontSize = 14.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)',
)

# Typography/visual tokens: previous fixes may already be present.
ui = "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt"
for old, new in [
    ('val ShadowAmbient = Color(0x0F142033)', 'val ShadowAmbient = Color(0x0C3B82C4)'),
    ('val ShadowSpot = Color(0x17142033)', 'val ShadowSpot = Color(0x123B82C4)'),
    ('val BackgroundTop = Color(0xFFEAF3FF)', 'val BackgroundTop = Color(0xFFE8F4FF)'),
    ('val BackgroundMid = Color(0xFFF2F7FD)', 'val BackgroundMid = Color(0xFFF5FAFF)'),
    ('val CardTop = Color(0xFFFCFEFF)', 'val CardTop = Color(0xFFFFFFFF)'),
    ('val CardBottom = Color(0xFFF3F7FC)', 'val CardBottom = Color(0xFFFBFDFF)'),
    ('val FieldSoft = Color(0xFFF7FAFE)', 'val FieldSoft = Color(0xFFF6FAFF)'),
    ('val Border = Color(0xFFDDE7F2)', 'val Border = Color(0xFFD8E8F7)'),
    ('val BorderStrong = Color(0xFFCAD8E8)', 'val BorderStrong = Color(0xFFC4DCF2)'),
    ('val Inner = Color(0xFFF8FAFC)', 'val Inner = Color(0xFFF3F9FF)'),
    ('val Border = Color(0xFFE7EDF4)', 'val Border = Color(0xFFDDEAF6)'),
    (
        'fontSize = 22.sp,\n        lineHeight = 28.sp,\n        fontWeight = FontWeight.Bold,',
        'fontSize = 20.sp,\n        lineHeight = 25.sp,\n        fontWeight = FontWeight.Bold,\n        letterSpacing = (-0.12).sp,',
    ),
    (
        'fontSize = 20.sp,\n        lineHeight = 26.sp,\n        fontWeight = FontWeight.Bold,',
        'fontSize = 18.sp,\n        lineHeight = 23.sp,\n        fontWeight = FontWeight.Bold,\n        letterSpacing = (-0.10).sp,',
    ),
    (
        'fontSize = 16.sp,\n        lineHeight = 21.sp,\n        fontWeight = FontWeight.Bold,',
        'fontSize = 15.sp,\n        lineHeight = 20.sp,\n        fontWeight = FontWeight.Bold,\n        letterSpacing = (-0.06).sp,',
    ),
    (
        'fontSize = 14.sp,\n        lineHeight = 19.sp,\n        fontWeight = FontWeight.SemiBold,',
        'fontSize = 13.5.sp,\n        lineHeight = 18.sp,\n        fontWeight = FontWeight.SemiBold,\n        letterSpacing = (-0.03).sp,',
    ),
]:
    ensure_replace(ui, old, new)

# New request: 10台 / 2条 a little smaller while retaining Bold.
ensure_replace(
    ui,
    'val HomeMiniMetric = Metric.copy(fontSize = 28.sp, lineHeight = 32.sp)',
    'val HomeMiniMetric = Metric.copy(fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)',
)

# Device card: right download column begins on the same half-grid as IPv6 / signal.
main = "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
text = read(main)
if 'modifier = Modifier.width(64.dp)' not in text or 'label = "下行"' not in text:
    pattern = r'@Composable\nfun DeviceTodayTrafficBar\(d: DeviceItem\) \{.*?\n\}\n\n@Composable\nprivate fun DeviceTrafficDirection'
    replacement = r'''@Composable
fun DeviceTodayTrafficBar(d: DeviceItem) {
    val upload = cleanApiText(d.todayUpload)
    val download = cleanApiText(d.todayDownload)
    if (upload.isBlank() && download.isBlank()) return

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "今日流量",
                modifier = Modifier.width(64.dp),
                fontSize = LabTypography.Caption.fontSize,
                fontWeight = FontWeight.Medium,
                color = LabV2.InkMuted,
                maxLines = 1,
            )
            DeviceTrafficDirection(
                label = "上行",
                value = upload.ifBlank { "--" },
                icon = Icons.Rounded.ArrowUpward,
                color = Color(0xFFF59E0B),
                modifier = Modifier.weight(1f),
            )
        }
        DeviceTrafficDirection(
            label = "下行",
            value = download.ifBlank { "--" },
            icon = Icons.Rounded.ArrowDownward,
            color = Color(0xFF06B6D4),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DeviceTrafficDirection'''
    text2, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{main}: DeviceTodayTrafficBar replacement count={count}")
    write(main, text2)

# Health score card: remove the hard outline that reads as a horizontal line;
# preserve subtle depth, and slightly reduce vertical space around the ring.
text = read(main)
start = text.index('fun HealthScoreCard(')
end = text.find('\n@Composable\n', start + 20)
if end < 0:
    raise RuntimeError('HealthScoreCard end marker missing')
score = text[start:end]
if 'border = null' not in score:
    score2, count = re.subn(
        r'shadowElevation = 0\.dp,\s*border = androidx\.compose\.foundation\.BorderStroke\(1\.dp, HomeCardBorder\)',
        'shadowElevation = 1.dp,\n        border = null',
        score,
        count=1,
    )
    if count != 1:
        raise RuntimeError('HealthScoreCard border replacement failed')
    score = score2
if 'vertical = 8.dp' not in score:
    if 'vertical = 11.dp' not in score:
        raise RuntimeError('HealthScoreCard vertical padding marker missing')
    score = score.replace('vertical = 11.dp', 'vertical = 8.dp', 1)
if 'Modifier.size(124.dp)' not in score:
    score2, count = re.subn(
        r'Modifier\.size\(132\.dp\)(\.clickable\s*\{[^}]*health_score[^}]*\})',
        r'Modifier.size(124.dp)\1',
        score,
        count=1,
    )
    if count != 1:
        raise RuntimeError('HealthScoreCard gauge container replacement failed')
    score = score2
text = text[:start] + score + text[end:]
write(main, text)

# Router settings/control: prior unified icons are accepted; patch only if still old.
router = "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
for old, new in [
    ('private val RouterField = Color(0xFFF7F9FD)', 'private val RouterField = Color(0xFFFBFDFF)'),
    ('private val RouterBorder = Color(0xFFE4EAF3)', 'private val RouterBorder = Color(0xFFD9E8F7)'),
    ('private val RouterPage = Color(0xFFF5F8FD)', 'private val RouterPage = Color(0xFFF2F8FF)'),
]:
    ensure_replace(router, old, new)

# Existing final markers prove mapping + connection icon unification already landed.
require(router, 'One shared bidirectional mapping symbol across settings, IPv6 mapping and native port mapping.')
require(router, 'Shared double-lightning symbol for router-control health everywhere.')
require(router, 'Icon(Icons.Rounded.CompareArrows, null, Modifier.size(20.dp), tint = RouterBlue)')

portmap = "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"
if 'Icons.Rounded.SwapHoriz' in read(portmap):
    write(portmap, read(portmap).replace('Icons.Rounded.SwapHoriz', 'Icons.Rounded.CompareArrows'))
require(portmap, 'Icons.Rounded.CompareArrows')

settings = "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"
if 'RouterGlyphIcon(RouterGlyph.Connection, accent, Modifier.size(23.dp))' not in read(settings):
    text = read(settings)
    text2, count = re.subn(
        r'Box\(Modifier\.size\(23\.dp\)\) \{\s*Icon\(Icons\.Rounded\.Bolt, null, Modifier\.align\(Alignment\.CenterStart\)\.size\(16\.dp\), tint = accent\)\s*Icon\(Icons\.Rounded\.Bolt, null, Modifier\.align\(Alignment\.CenterEnd\)\.size\(16\.dp\), tint = accent\)\s*\}',
        'RouterGlyphIcon(RouterGlyph.Connection, accent, Modifier.size(23.dp))',
        text,
        count=1,
        flags=re.S,
    )
    if count != 1:
        raise RuntimeError('Router settings connection icon replacement failed')
    write(settings, text2)

# Favorite editor / router native DDNS linkage: previous fixes are accepted.
fav = "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt"
require(fav, 'private fun routerDdnsHostname(record: DdnsRecord): String?')
require(fav, 'routerDdnsRecord(value, nativeDdnsRecords)?.let(::routerDdnsHostname)')
require(fav, 'selectDdns(record?.let(::routerDdnsId), record?.let(::routerDdnsHostname))')
require(fav, 'Modifier.weight(1f).height(LabV2.FieldHeight)')
require(fav, 'color = LabV2.Ink, fontWeight = FontWeight.SemiBold')

# Launcher: if the richer teal-blue background already landed, just verify it.
launcher = "app/src/main/res/drawable/ic_launcher_background.xml"
if '#FF76DDD3' not in read(launcher):
    write(launcher, '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient android:startX="4" android:startY="2" android:endX="104" android:endY="106" android:type="linear">
                <item android:color="#FF76DDD3" android:offset="0" />
                <item android:color="#FF53C8CC" android:offset="0.38" />
                <item android:color="#FF36B2C5" android:offset="0.72" />
                <item android:color="#FF259BB8" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient android:centerX="32" android:centerY="26" android:gradientRadius="82" android:type="radial">
                <item android:color="#30FFFFFF" android:offset="0" />
                <item android:color="#10FFFFFF" android:offset="0.46" />
                <item android:color="#00FFFFFF" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
''')

# Final assertions covering the whole requested set.
for name, needles in {
    gradle: ['versionCode = 184', 'versionName = "0.10.42"'],
    wol: ['Text("WOL 设备", fontSize = 14.5.sp', 'fontWeight = FontWeight.Bold'],
    ui: ['HomeMiniMetric = Metric.copy(fontSize = 25.sp', 'fontSize = 18.sp', 'fontSize = 15.sp', 'Color(0xFFF3F9FF)'],
    main: ['modifier = Modifier.width(64.dp)', 'label = "下行"', 'Modifier.size(124.dp)', 'border = null'],
    router: ['One shared bidirectional mapping symbol', 'Shared double-lightning symbol', 'Icons.Rounded.CompareArrows'],
    portmap: ['Icons.Rounded.CompareArrows'],
    settings: ['RouterGlyphIcon(RouterGlyph.Connection, accent, Modifier.size(23.dp))'],
    fav: ['routerDdnsHostname', 'Modifier.weight(1f).height(LabV2.FieldHeight)'],
}.items():
    for needle in needles:
        require(name, needle)

print('v0.10.42 / build184 final patch is complete and ready for compile')
