#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
PORT = ROOT / "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"
NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "app/build.gradle.kts"
ICON_SOURCE = ROOT / "labprobe_icon_1024.png"
ICON_TARGET = ROOT / "app/src/main/res/drawable-nodpi/labprobe_launcher_1024.png"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    old_count = text.count(old)
    new_count = text.count(new)
    if old_count == 1:
        return text.replace(old, new, 1)
    if old_count == 0 and new_count >= 1:
        return text
    raise SystemExit(f"{label}: expected old=1 or already-patched new>=1, found old={old_count}, new={new_count}")


def replace_after(text: str, marker: str, old: str, new: str, label: str) -> str:
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"{label}: marker not found: {marker}")
    old_pos = text.find(old, start)
    new_pos = text.find(new, start)
    if old_pos >= 0 and (new_pos < 0 or old_pos < new_pos):
        return text[:old_pos] + new + text[old_pos + len(old):]
    if new_pos >= 0:
        return text
    raise SystemExit(f"{label}: neither old nor new form found after marker")


main = MAIN.read_text(encoding="utf-8")

# 首页卡片：只增强首页内容卡片本身，不改工具页/其他页面卡片。
main = replace_once(
    main,
    """        modifier = modifier.fillMaxWidth().shadow(
            2.dp,
            shape,
            clip = false,
            ambientColor = LabV2.ShadowAmbient,
            spotColor = LabV2.ShadowSpot,""",
    """        modifier = modifier.fillMaxWidth().shadow(
            5.dp,
            shape,
            clip = false,
            ambientColor = LabV2.ShadowAmbient.copy(alpha = .85f),
            spotColor = LabV2.ShadowSpot.copy(alpha = .95f),""",
    "home HealthCard elevation",
)
main = replace_once(
    main,
    """        modifier = Modifier.fillMaxWidth().shadow(
            2.dp,
            shape,
            clip = false,
            ambientColor = LabV2.ShadowAmbient,
            spotColor = LabV2.ShadowSpot,""",
    """        modifier = Modifier.fillMaxWidth().shadow(
            5.dp,
            shape,
            clip = false,
            ambientColor = LabV2.ShadowAmbient.copy(alpha = .85f),
            spotColor = LabV2.ShadowSpot.copy(alpha = .95f),""",
    "home HealthScoreCard elevation",
)

# DetailShell 只增加可选参数；默认值保持现有页面完全不变。
main = replace_once(
    main,
    """    compactHeader: Boolean = false,
    unifiedTypography: Boolean = false,
    showHeader: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {""",
    """    compactHeader: Boolean = false,
    unifiedTypography: Boolean = false,
    showHeader: Boolean = true,
    sectionGap: Dp = LabV2.SectionGap,
    titleStyleOverride: androidx.compose.ui.text.TextStyle? = null,
    subtitleStyleOverride: androidx.compose.ui.text.TextStyle? = null,
    content: @Composable ColumnScope.() -> Unit
) {""",
    "DetailShell optional overrides",
)
main = replace_after(
    main,
    "fun DetailShell(",
    "verticalArrangement = Arrangement.spacedBy(LabV2.SectionGap)",
    "verticalArrangement = Arrangement.spacedBy(sectionGap)",
    "DetailShell section gap",
)
main = replace_after(
    main,
    "fun DetailShell(",
    """                titleStyle = LabTypography.PageTitle.takeIf { unifiedTypography },
                subtitleStyle = LabTypography.Supporting.takeIf { unifiedTypography }""",
    """                titleStyle = titleStyleOverride ?: LabTypography.PageTitle.takeIf { unifiedTypography },
                subtitleStyle = subtitleStyleOverride ?: LabTypography.Supporting.takeIf { unifiedTypography }""",
    "DetailShell typography overrides",
)

# 设备页：只给“终端同步”和设备卡片增加悬浮层级。
main = replace_once(
    main,
    """                Icons.Rounded.Devices,
                Color(0xFFF59E0B),
                coreSurface = true,
                headerAction = {""",
    """                Icons.Rounded.Devices,
                Color(0xFFF59E0B),
                modifier = Modifier.shadow(
                    4.dp,
                    LabCoreSurface.CardShape,
                    clip = false,
                    ambientColor = LabV2.ShadowAmbient,
                    spotColor = LabV2.ShadowSpot
                ),
                coreSurface = true,
                headerAction = {""",
    "device sync card elevation",
)
main = replace_once(
    main,
    """        icon = profile.icon,
        accent = profile.accent,
        iconKey = profile.iconKey,
        coreSurface = true,
        headerAction = {""",
    """        icon = profile.icon,
        accent = profile.accent,
        iconKey = profile.iconKey,
        modifier = Modifier.shadow(
            4.dp,
            LabCoreSurface.CardShape,
            clip = false,
            ambientColor = LabV2.ShadowAmbient,
            spotColor = LabV2.ShadowSpot
        ),
        coreSurface = true,
        headerAction = {""",
    "device item card elevation",
)

# 记录页：只把列表项间距从 7dp 收紧为 3dp，卡片高度/手势不动。
main = replace_after(
    main,
    "fun EventsScreen(",
    "verticalArrangement = Arrangement.spacedBy(LabV2.ListGap)",
    "verticalArrangement = Arrangement.spacedBy(3.dp)",
    "record list gap",
)

# 设置页：三个指定按钮统一薄荷绿，不影响其他按钮。
main = replace_after(
    main,
    "fun SettingsScreen(",
    """    var msg by remember { mutableStateOf("") }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()""",
    """    var msg by remember { mutableStateOf("") }
    val settingsMint = Color(0xFF28BFA3)
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()""",
    "settings mint token",
)
main = replace_after(
    main,
    "fun SettingsScreen(",
    "colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))",
    "colors = ButtonDefaults.buttonColors(containerColor = settingsMint)",
    "settings save button",
)
main = replace_after(
    main,
    "fun SettingsScreen(",
    "colors = ButtonDefaults.buttonColors(containerColor = LabV2.Cyan)",
    "colors = ButtonDefaults.buttonColors(containerColor = settingsMint)",
    "settings calibrate button",
)
main = replace_after(
    main,
    "fun SettingsScreen(",
    """PillButton(if (privacy) "关闭隐私模式" else "开启隐私模式", Icons.Rounded.VpnKey, accent = LabV2.Cyan)""",
    """PillButton(if (privacy) "关闭隐私模式" else "开启隐私模式", Icons.Rounded.VpnKey, accent = settingsMint)""",
    "settings privacy button",
)
MAIN.write_text(main, encoding="utf-8")

port = PORT.read_text(encoding="utf-8")

# 图3：列表页去掉额外 Spacer，并把该页局部 section gap 收紧到 6dp。
port = replace_once(
    port,
    """DetailShell("端口映射", "IPv6 入口 · Rust 四层反代 · 6→4 / 6→6", onBack, unifiedTypography = true, showHeader = !embedded) {
        Spacer(Modifier.height(8.dp))
        PortMapAgentCard(agent, loading)""",
    """DetailShell("端口映射", "IPv6 入口 · Rust 四层反代 · 6→4 / 6→6", onBack, unifiedTypography = true, showHeader = !embedded, sectionGap = 6.dp) {
        PortMapAgentCard(agent, loading)""",
    "port-map list spacing",
)

# 图1：详情标题/副标题小一号，顶部空白收紧；仅该详情页生效。
port = replace_once(
    port,
    """DetailShell(rule.name, "${rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) }} · ${rule.transportProtocol.ifBlank { "TCP" }} · ${rule.modeText}${if (rule.targetMode == "ipv6_suffix") " · IPv6 后缀匹配" else ""}", onDismiss, unifiedTypography = true) {
            Spacer(Modifier.height(6.dp))
            LabCoreCard(compact = true) {""",
    """DetailShell(
        rule.name,
        "${rule.serviceType.ifBlank { defaultPortMapServiceType(rule.targetPort, rule.transportProtocol) }} · ${rule.transportProtocol.ifBlank { "TCP" }} · ${rule.modeText}${if (rule.targetMode == "ipv6_suffix") " · IPv6 后缀匹配" else ""}",
        onDismiss,
        unifiedTypography = true,
        sectionGap = 6.dp,
        titleStyleOverride = LabTypography.CardTitle,
        subtitleStyleOverride = LabTypography.Caption
    ) {
            LabCoreCard(compact = true) {""",
    "port-map detail header and spacing",
)
port = replace_once(
    port,
    """Text(value, fontSize = LabTypography.SectionTitle.fontSize, lineHeight = LabTypography.SectionTitle.lineHeight, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)""",
    """Text(value, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)""",
    "port-map metric value size",
)
PORT.write_text(port, encoding="utf-8")

native = NATIVE.read_text(encoding="utf-8")

# 图2：NAT“分析结果”专用行样式。其他 NativeValueRow 页面保持原样。
analysis_helper = """
@Composable
private fun NativeAnalysisValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            Modifier.width(78.dp),
            style = LabTypography.Value.copy(color = NativeInk, fontWeight = FontWeight.Bold)
        )
        Text(
            value,
            Modifier.weight(1f),
            style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium),
            maxLines = 3,
            overflow = TextOverflow.Clip
        )
    }
}
"""
if "private fun NativeAnalysisValueRow(" not in native:
    anchor = """@Composable
private fun NativeMessage(text: String, color: Color) {"""
    pos = native.find(anchor)
    if pos < 0:
        raise SystemExit("NAT analysis helper anchor not found")
    native = native[:pos] + analysis_helper + "\n" + native[pos:]

nat_start = native.find('NativeTitle(Icons.Rounded.Analytics, "分析结果", NativeGreen)')
nat_end = native.find("\n        }\n\n        if (result.log.isNotBlank())", nat_start)
if nat_start < 0 or nat_end < 0:
    raise SystemExit("NAT analysis card block not found")
nat_block = native[nat_start:nat_end]
if "NativeAnalysisValueRow(" not in nat_block:
    nat_block = nat_block.replace("NativeValueRow(", "NativeAnalysisValueRow(")
    native = native[:nat_start] + nat_block + native[nat_end:]
NATIVE.write_text(native, encoding="utf-8")

# 使用仓库根目录中此前生成并保留的 1024 图标作为实际 Launcher 图标。
manifest = MANIFEST.read_text(encoding="utf-8")
manifest = replace_once(
    manifest,
    'android:icon="@mipmap/ic_launcher"',
    'android:icon="@drawable/labprobe_launcher_1024"',
    "launcher icon manifest",
)
manifest = replace_once(
    manifest,
    'android:roundIcon="@mipmap/ic_launcher_round"',
    'android:roundIcon="@drawable/labprobe_launcher_1024"',
    "round launcher icon manifest",
)
MANIFEST.write_text(manifest, encoding="utf-8")

if not ICON_SOURCE.exists():
    raise SystemExit("labprobe_icon_1024.png is missing")
ICON_TARGET.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(ICON_SOURCE, ICON_TARGET)

# 新 APK 使用唯一 versionCode，versionName 不动。
gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, "versionCode = 186", "versionCode = 187", "version code")
GRADLE.write_text(gradle, encoding="utf-8")

print("build187 targeted UI polish applied")
