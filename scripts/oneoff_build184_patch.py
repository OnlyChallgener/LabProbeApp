from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def path(name: str) -> Path:
    return ROOT / name


def read(name: str) -> str:
    return path(name).read_text(encoding="utf-8")


def write(name: str, text: str) -> None:
    path(name).write_text(text, encoding="utf-8")


def exact(name: str, old: str, new: str, expected: int = 1) -> None:
    text = read(name)
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{name}: expected {expected} matches, got {count}: {old[:100]!r}")
    write(name, text.replace(old, new, expected))


def regex(name: str, pattern: str, repl: str, expected: int = 1, flags=re.S) -> None:
    text = read(name)
    new, count = re.subn(pattern, repl, text, count=expected, flags=flags)
    if count != expected:
        raise RuntimeError(f"{name}: expected {expected} regex matches, got {count}: {pattern[:120]!r}")
    write(name, new)


# ---------------------------------------------------------------------------
# 1. Version: requested public version v0.10.42. Keep build code monotonic.
# ---------------------------------------------------------------------------
gradle = "app/build.gradle.kts"
exact(
    gradle,
    'versionCode = 181\n        versionName = "0.10.39"',
    'versionCode = 184\n        versionName = "0.10.42"',
)


# ---------------------------------------------------------------------------
# 2. WOL page header: smaller, strong rather than oversized/black.
# ---------------------------------------------------------------------------
wol = "app/src/main/kotlin/com/labprobe/app/WolManagementPanel.kt"
exact(
    wol,
    'Text("WOL 设备", fontSize = 16.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black, color = LabV2.Ink)',
    'Text("WOL 设备", fontSize = 14.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = LabV2.Ink)',
)


# ---------------------------------------------------------------------------
# 3. Typography + visual tokens.
#    Do NOT enlarge titles. Reduce title sizes slightly, preserve stronger weight,
#    add tiny negative tracking for a less rigid feel, and brighten core surfaces.
# ---------------------------------------------------------------------------
ui = "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt"
text = read(ui)
changes = [
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
    # User's newest request: 10台 / 2条 only a little smaller, never thinner.
    (
        'val HomeMiniMetric = Metric.copy(fontSize = 28.sp, lineHeight = 32.sp)',
        'val HomeMiniMetric = Metric.copy(fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)',
    ),
]
for old, new in changes:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{ui}: token expected once, got {count}: {old!r}")
    text = text.replace(old, new, 1)
text = text.replace(
    '// Shared neutral elevation: no blue, white, or feature-colored shadows.',
    '// Shared cool-blue elevation: soft depth without grey square shadows.',
    1,
)
write(ui, text)


# ---------------------------------------------------------------------------
# 4. Device card today-traffic alignment.
#    Right half starts exactly at the same half-column as IPv6/signal above.
# ---------------------------------------------------------------------------
main = "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
device_today = r'''@Composable
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
regex(
    main,
    r'@Composable\nfun DeviceTodayTrafficBar\(d: DeviceItem\) \{.*?\n\}\n\n@Composable\nprivate fun DeviceTrafficDirection',
    device_today,
)

# New score-card request: no hard outline/horizontal-looking border; keep a very
# soft depth cue. Reduce top/bottom space around the gauge without crowding it.
text = read(main)
score_start = text.index('fun HealthScoreCard(')
score_end = text.index('\n@Composable\n', score_start + 10)
score = text[score_start:score_end]
old = 'shadowElevation = 0.dp,\n        border = androidx.compose.foundation.BorderStroke(1.dp, HomeCardBorder)'
if old not in score:
    raise RuntimeError('HealthScoreCard surface border marker missing')
score = score.replace(old, 'shadowElevation = 1.dp,\n        border = null', 1)
old = 'Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {'
if old not in score:
    raise RuntimeError('HealthScoreCard padding marker missing')
score = score.replace(old, 'Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {', 1)
old = 'Box(Modifier.size(132.dp).clickable { onNavigate("health_score") }, contentAlignment = Alignment.Center) {'
if old not in score:
    raise RuntimeError('HealthScoreCard gauge container marker missing')
score = score.replace(old, 'Box(Modifier.size(124.dp).clickable { onNavigate("health_score") }, contentAlignment = Alignment.Center) {', 1)
text = text[:score_start] + score + text[score_end:]
write(main, text)


# ---------------------------------------------------------------------------
# 5. Router settings/control: lighter page and truly shared icon language.
# ---------------------------------------------------------------------------
router = "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
exact(router, 'private val RouterField = Color(0xFFF7F9FD)', 'private val RouterField = Color(0xFFFBFDFF)')
exact(router, 'private val RouterBorder = Color(0xFFE4EAF3)', 'private val RouterBorder = Color(0xFFD9E8F7)')
exact(router, 'private val RouterPage = Color(0xFFF5F8FD)', 'private val RouterPage = Color(0xFFF2F8FF)')

mapping_block = '''            RouterGlyph.Mapping, RouterGlyph.Port -> {
                // Shared bidirectional mapping symbol across all mapping surfaces.
                val left = w * .18f
                val right = w * .82f
                val upper = h * .36f
                val lower = h * .64f
                drawLine(color, Offset(left, upper), Offset(right, upper), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(right - w * .13f, upper - h * .10f), Offset(right, upper), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(right - w * .13f, upper + h * .10f), Offset(right, upper), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(right, lower), Offset(left, lower), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(left + w * .13f, lower - h * .10f), Offset(left, lower), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(left + w * .13f, lower + h * .10f), Offset(left, lower), stroke.width, StrokeCap.Round)
            }
            RouterGlyph.Ddns -> {'''
regex(
    router,
    r'            RouterGlyph\.Mapping, RouterGlyph\.Port -> \{.*?\n            \}\n            RouterGlyph\.Ddns -> \{',
    mapping_block,
)

connection_block = '''            RouterGlyph.Connection -> {
                // Shared double-lightning symbol for router-control health everywhere.
                fun bolt(cx: Float): Path = Path().apply {
                    moveTo(cx + w * .04f, h * .12f)
                    lineTo(cx - w * .09f, h * .50f)
                    lineTo(cx - w * .01f, h * .50f)
                    lineTo(cx - w * .06f, h * .88f)
                    lineTo(cx + w * .11f, h * .42f)
                    lineTo(cx + w * .02f, h * .42f)
                    close()
                }
                drawPath(bolt(w * .34f), color)
                drawPath(bolt(w * .66f), color)
            }
            RouterGlyph.Beta -> {'''
regex(
    router,
    r'            RouterGlyph\.Connection -> \{.*?\n            \}\n            RouterGlyph\.Beta -> \{',
    connection_block,
)
# Native router port-mapping list should use the same recognizable double-arrow icon.
exact(
    router,
    'RouterGlyphIcon(RouterGlyph.Port, RouterBlue, Modifier.size(20.dp))',
    'Icon(Icons.Rounded.CompareArrows, null, Modifier.size(20.dp), tint = RouterBlue)',
)

# IPv6 mapping Agent / empty-state currently use SwapHoriz; standardize to CompareArrows.
portmap = "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"
text = read(portmap)
count = text.count('Icons.Rounded.SwapHoriz')
if count < 2:
    raise RuntimeError(f"{portmap}: expected at least 2 SwapHoriz icons, got {count}")
write(portmap, text.replace('Icons.Rounded.SwapHoriz', 'Icons.Rounded.CompareArrows'))

# Router settings connection card already draws two Bolts manually; replace it by
# the same RouterGlyph.Connection used by Hub status so both screens cannot drift.
settings = "app/src/main/kotlin/com/labprobe/app/RouterSettingsUi.kt"
regex(
    settings,
    r'\s*Box\(Modifier\.size\(23\.dp\)\) \{\s*Icon\(Icons\.Rounded\.Bolt, null, Modifier\.align\(Alignment\.CenterStart\)\.size\(16\.dp\), tint = accent\)\s*Icon\(Icons\.Rounded\.Bolt, null, Modifier\.align\(Alignment\.CenterEnd\)\.size\(16\.dp\), tint = accent\)\s*\}',
    '\n                RouterGlyphIcon(RouterGlyph.Connection, accent, Modifier.size(23.dp))',
)


# ---------------------------------------------------------------------------
# 6. Favorite editor: equal service/association fields + real router DDNS linkage.
#    Router native API represents records like "rj.lab86@shinya.icu"; convert
#    that record+zone notation to the actual hostname before building remote URL.
# ---------------------------------------------------------------------------
fav = "app/src/main/kotlin/com/labprobe/app/FavoriteShortcuts.kt"
text = read(fav)
func_start = text.index('private fun RowScope.FavoriteInlineField(')
func_end = text.index('\n}\n\n', func_start) + 2
func = text[func_start:func_end]
old = 'modifier = Modifier.weight(1f),'
if func.count(old) != 1:
    raise RuntimeError(f'FavoriteInlineField modifier marker count={func.count(old)}')
func = func.replace(old, 'modifier = Modifier.weight(1f).height(LabV2.FieldHeight),', 1)
text = text[:func_start] + func + text[func_end:]
write(fav, text)

# Association outlined button gets the same border/height/content geometry.
exact(
    fav,
    'colors = ButtonDefaults.outlinedButtonColors(contentColor = LabV2.Ink),\n                    ) {',
    'colors = ButtonDefaults.outlinedButtonColors(contentColor = LabV2.Ink),\n                        border = BorderStroke(1.dp, LabV2.BorderStrong),\n                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),\n                    ) {',
)

text = read(fav)
marker = 'private fun favoriteDdnsHostname(\n'
if marker not in text:
    raise RuntimeError('favoriteDdnsHostname marker missing')
helper = '''private fun routerDdnsHostname(record: DdnsRecord): String? {
    val raw = record.domain.trim()
    validFavoriteHostname(raw)?.let { return it }
    val at = raw.indexOf('@')
    if (at > 0 && at < raw.lastIndex) {
        val rr = raw.substring(0, at).trim().trim('.')
        val zone = raw.substring(at + 1).trim().trim('.')
        validFavoriteHostname("$rr.$zone")?.let { return it }
    }
    return null
}

'''
text = text.replace(marker, helper + marker, 1)
old = '?: routerDdnsRecord(value, nativeDdnsRecords)?.domain?.let(::validFavoriteHostname)'
if old not in text:
    raise RuntimeError('router native DDNS hostname fallback marker missing')
text = text.replace(old, '?: routerDdnsRecord(value, nativeDdnsRecords)?.let(::routerDdnsHostname)', 1)
old = 'fun selectRouterDdns(record: DdnsRecord?) =\n        selectDdns(record?.let(::routerDdnsId), record?.domain)'
if old not in text:
    raise RuntimeError('selectRouterDdns marker missing')
text = text.replace(
    old,
    'fun selectRouterDdns(record: DdnsRecord?) =\n        selectDdns(record?.let(::routerDdnsId), record?.let(::routerDdnsHostname))',
    1,
)
old = 'Text(record.domain, color = LabV2.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)'
if old not in text:
    raise RuntimeError('router DDNS menu title marker missing')
text = text.replace(
    old,
    'Text(record.domain, color = LabV2.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)',
    1,
)
write(fav, text)


# ---------------------------------------------------------------------------
# 7. Launcher: preserve foreground geometry/size, only improve color depth.
# ---------------------------------------------------------------------------
launcher = path('app/src/main/res/drawable/ic_launcher_background.xml')
launcher.write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:startX="4"
                android:startY="2"
                android:endX="104"
                android:endY="106"
                android:type="linear">
                <item android:color="#FF76DDD3" android:offset="0" />
                <item android:color="#FF53C8CC" android:offset="0.38" />
                <item android:color="#FF36B2C5" android:offset="0.72" />
                <item android:color="#FF259BB8" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:centerX="32"
                android:centerY="26"
                android:gradientRadius="82"
                android:type="radial">
                <item android:color="#30FFFFFF" android:offset="0" />
                <item android:color="#10FFFFFF" android:offset="0.46" />
                <item android:color="#00FFFFFF" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
''', encoding='utf-8')


# ---------------------------------------------------------------------------
# Final assertions. Fail before touching Git history if any requested fix is absent.
# ---------------------------------------------------------------------------
checks = {
    gradle: ['versionCode = 184', 'versionName = "0.10.42"'],
    wol: ['fontSize = 14.5.sp', 'fontWeight = FontWeight.Bold'],
    ui: ['fontSize = 18.sp', 'fontSize = 15.sp', 'HomeMiniMetric = Metric.copy(fontSize = 25.sp', 'Color(0xFFF3F9FF)'],
    main: ['label = "下行"', 'Modifier.size(124.dp).clickable', 'border = null'],
    router: ['Shared bidirectional mapping symbol', 'Shared double-lightning symbol', 'Icons.Rounded.CompareArrows'],
    portmap: ['Icons.Rounded.CompareArrows'],
    settings: ['RouterGlyphIcon(RouterGlyph.Connection, accent, Modifier.size(23.dp))'],
    fav: ['routerDdnsHostname', 'Modifier.weight(1f).height(LabV2.FieldHeight)', 'color = LabV2.Ink, fontWeight = FontWeight.SemiBold'],
}
for name, needles in checks.items():
    content = read(name)
    for needle in needles:
        if needle not in content:
            raise RuntimeError(f"{name}: missing final assertion {needle!r}")

print('build184 / v0.10.42 targeted patch applied successfully')
