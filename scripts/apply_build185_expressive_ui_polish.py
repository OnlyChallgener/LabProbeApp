from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(name: str) -> str:
    return (ROOT / name).read_text(encoding="utf-8")


def write(name: str, text: str) -> None:
    (ROOT / name).write_text(text, encoding="utf-8")


def replace_once(name: str, old: str, new: str) -> None:
    text = read(name)
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{name}: expected exactly one match, got {count}: {old[:140]!r}")
    write(name, text.replace(old, new, 1))


def replace_all(name: str, old: str, new: str, minimum: int = 1) -> None:
    text = read(name)
    if old not in text:
        if new in text:
            return
        raise RuntimeError(f"{name}: neither old nor new marker found: {old!r}")
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f"{name}: expected at least {minimum} matches, got {count}: {old!r}")
    write(name, text.replace(old, new))


def replace_in_block(name: str, start: str, end: str, old: str, new: str) -> None:
    text = read(name)
    start_at = text.index(start)
    end_at = text.index(end, start_at)
    block = text[start_at:end_at]
    if new in block:
        return
    count = block.count(old)
    if count != 1:
        raise RuntimeError(f"{name}: block {start!r} expected one {old!r}, got {count}")
    block = block.replace(old, new, 1)
    write(name, text[:start_at] + block + text[end_at:])


# ---------------------------------------------------------------------------
# 1) Upgrade build code while keeping the requested public v0.10.42 line.
# ---------------------------------------------------------------------------
gradle = "app/build.gradle.kts"
replace_once(
    gradle,
    'versionCode = 184\n        versionName = "0.10.42"',
    'versionCode = 185\n        versionName = "0.10.42"',
)

# ---------------------------------------------------------------------------
# 2) Typography: use the platform/OEM default family everywhere the shared
#    design system explicitly selected generic SansSerif. Stronger weights,
#    slightly tighter hierarchy, no title-size inflation.
# ---------------------------------------------------------------------------
ui = "app/src/main/kotlin/com/labprobe/app/ui/design/LabUiV2.kt"
replace_all(ui, "FontFamily.SansSerif", "FontFamily.Default", minimum=10)
replace_once(ui, "val Purple = Color(0xFF7456D8)", "val Purple = Cyan")
replace_in_block(ui, "val AppTitle = TextStyle(", "val PageTitle = TextStyle(", "fontWeight = FontWeight.Bold", "fontWeight = FontWeight.ExtraBold")
replace_in_block(ui, "val PageTitle = TextStyle(", "val CardTitle = TextStyle(", "fontWeight = FontWeight.Bold", "fontWeight = FontWeight.ExtraBold")
replace_in_block(ui, "val CardTitle = TextStyle(", "val SectionTitle = TextStyle(", "letterSpacing = (-0.06).sp", "letterSpacing = (-0.08).sp")
replace_in_block(ui, "val SectionTitle = TextStyle(", "val Body = TextStyle(", "fontWeight = FontWeight.SemiBold", "fontWeight = FontWeight.Bold")
replace_once(ui, "val ValueStrong = Value.copy(fontWeight = FontWeight.SemiBold)", "val ValueStrong = Value.copy(fontWeight = FontWeight.Bold)")
replace_in_block(ui, "val Button = TextStyle(", "val CompactButton = TextStyle(", "fontWeight = FontWeight.SemiBold", "fontWeight = FontWeight.Bold")
replace_in_block(ui, "val CompactButton = TextStyle(", "val FieldLabel =", "fontWeight = FontWeight.SemiBold", "fontWeight = FontWeight.Bold")
replace_in_block(ui, "val Metric = TextStyle(", "val CompactMetric =", "fontWeight = FontWeight.Bold", "fontWeight = FontWeight.ExtraBold")
replace_once(
    ui,
    "val HomeMiniMetric = Metric.copy(fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)",
    "val HomeMiniMetric = Metric.copy(fontSize = 22.sp, lineHeight = 25.sp, fontWeight = FontWeight.ExtraBold)",
)

# ---------------------------------------------------------------------------
# 3) Home: smaller mini metrics, no separator rules in Exit/Route card, and
#    platform default font for normal values while addresses stay monospace.
# ---------------------------------------------------------------------------
main = "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
replace_all(main, "FontFamily.SansSerif", "FontFamily.Default", minimum=1)
replace_all(main, "Color(0xFF7C3AED)", "LabV2.Cyan", minimum=1)
replace_once(
    main,
    '"v$NAME build$CODE · SSH、控件配色与设备名称一致性修复" to listOf(',
    '"v$NAME build$CODE · 字体、色彩、排版与中文提示统一优化" to listOf(',
)
replace_once(
    main,
    'if (index > 0) HorizontalDivider(color = HomeCardBorder, modifier = Modifier.padding(vertical = 7.dp))',
    'if (index > 0) Spacer(Modifier.height(5.dp))',
)
replace_once(
    main,
    'message.replace("刷新成功：", "最后刷新 ").ifBlank { lastRefresh.ifBlank { "等待同步" } },',
    'uiMessageZh(message).replace("刷新成功：", "最后刷新 ").ifBlank { lastRefresh.ifBlank { "等待同步" } },',
)
replace_once(
    main,
    'ExpressiveCard("状态总览", state.message, Icons.Rounded.Dashboard, Color(0xFF2D63D8)) {',
    'ExpressiveCard("状态总览", uiMessageZh(state.message), Icons.Rounded.Dashboard, Color(0xFF2D63D8)) {',
)
replace_once(
    main,
    'else -> reason.take(120)',
    'else -> uiMessageZh(reason).take(120)',
)
replace_once(
    main,
    'Text(connectionMessage, color =',
    'Text(uiMessageZh(connectionMessage), color =',
)
replace_once(
    main,
    '}.onFailure { msg = "校准失败：${it.message}" }',
    '}.onFailure { msg = "校准失败：${uiMessageZh(it.message.orEmpty())}" }',
)
replace_once(
    main,
    'ExpressiveCard("关于", "Kotlin + Compose + One UI 仪表盘风格", Icons.Rounded.Info, Color(0xFF64748B)) {',
    'ExpressiveCard("关于", "Kotlin + Compose · Material 3 Expressive × One UI", Icons.Rounded.Info, Color(0xFF64748B)) {',
)

# ---------------------------------------------------------------------------
# 4) Router settings/DDNS: translate raw transport messages at the shared
#    message surface and use cyan for Network Self-check's main action.
# ---------------------------------------------------------------------------
router = "app/src/main/kotlin/com/labprobe/app/RouterControlUi.kt"
replace_once(
    router,
    'containerColor=RouterBlue,contentColor=Color.White,disabledContainerColor=RouterBlue.copy(alpha=.62f),disabledContentColor=Color.White',
    'containerColor=RouterCyan,contentColor=Color.White,disabledContainerColor=RouterCyan.copy(alpha=.62f),disabledContentColor=Color.White',
)
replace_once(
    router,
    'Text(text,Modifier.padding(horizontal=10.dp,vertical=7.dp),fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=color)',
    'Text(uiMessageZh(text),Modifier.padding(horizontal=10.dp,vertical=7.dp),fontSize = LabTypography.Caption.fontSize,fontWeight=FontWeight.SemiBold,color=color)',
)
replace_once(
    router,
    'if (record.lastError.isNotBlank()) Text(record.lastError, fontSize = LabTypography.Caption.fontSize, color = RouterRed, fontWeight = FontWeight.SemiBold)',
    'if (record.lastError.isNotBlank()) Text(uiMessageZh(record.lastError), fontSize = LabTypography.Caption.fontSize, color = RouterRed, fontWeight = FontWeight.SemiBold)',
)

# ---------------------------------------------------------------------------
# 5) Router native tools: icon for STUN type, compact firmware version block,
#    residual NAT-log English cleanup, and shared Chinese message surface.
# ---------------------------------------------------------------------------
native = "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
replace_once(
    native,
    '    onSelect: (String) -> Unit,\n    modifier: Modifier = Modifier\n) {',
    '    onSelect: (String) -> Unit,\n    modifier: Modifier = Modifier,\n    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null\n) {',
)
replace_once(
    native,
    '        ) {\n            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {\n                Text(label, fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, color = NativeMuted, fontWeight = FontWeight.Medium)',
    '        ) {\n            if (leadingIcon != null) {\n                Icon(leadingIcon, null, Modifier.size(16.dp), tint = NativeBlue)\n                Spacer(Modifier.width(8.dp))\n            }\n            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {\n                Text(label, fontSize = LabTypography.Caption.fontSize, lineHeight = LabTypography.Caption.lineHeight, color = NativeMuted, fontWeight = FontWeight.Medium)',
)
replace_once(
    native,
    '                    onSelect = { mode = it },\n                    modifier = Modifier.weight(1.35f)\n                )',
    '                    onSelect = { mode = it },\n                    modifier = Modifier.weight(1.35f),\n                    leadingIcon = Icons.Rounded.Tune\n                )',
)
replace_once(
    native,
    '        .replace("Detection completed successfully", "检测成功完成", ignoreCase = true)\n    return text',
    '        .replace("Detection completed successfully", "检测成功完成", ignoreCase = true)\n        .replace(Regex("""(?i)(\\d+)\\s+attempts?""")) { match -> "${match.groupValues[1]} 次尝试" }\n    return text',
)
replace_once(
    native,
    '            NativeValueRow("当前版本", info.current.ifBlank { "--" }, stacked = true)',
    '            NativeFirmwareVersionRow(info.current.ifBlank { "--" })',
)
replace_once(
    native,
    '@Composable\nprivate fun NativeValueRow(label: String, value: String, stacked: Boolean = false) {',
    '''@Composable
private fun NativeFirmwareVersionRow(value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("当前版本", style = LabTypography.Supporting.copy(color = NativeMuted))
        Text(
            value,
            style = LabTypography.Supporting.copy(
                color = NativeInk,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp
            ),
            maxLines = 3,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun NativeValueRow(label: String, value: String, stacked: Boolean = false) {''',
)
replace_once(
    native,
    'Text(text, Modifier.fillMaxWidth().padding(10.dp), fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = color)',
    'Text(uiMessageZh(text), Modifier.fillMaxWidth().padding(10.dp), fontSize = LabTypography.Supporting.fontSize, fontWeight = FontWeight.SemiBold, color = color)',
)
replace_once(
    native,
    'task.failed -> task.message.ifBlank { task.stageText }\n                    else -> info.message.ifBlank { "尚未检测，点击下方按钮开始" }',
    'task.failed -> uiMessageZh(task.message.ifBlank { task.stageText })\n                    else -> uiMessageZh(info.message).ifBlank { "尚未检测，点击下方按钮开始" }',
)

# Defensive final assertions.
checks = {
    gradle: ['versionCode = 185', 'versionName = "0.10.42"'],
    ui: ['FontFamily.Default', 'HomeMiniMetric = Metric.copy(fontSize = 22.sp', 'fontWeight = FontWeight.ExtraBold', 'val Purple = Cyan'],
    main: ['Spacer(Modifier.height(5.dp))', 'uiMessageZh(connectionMessage)', 'Material 3 Expressive × One UI'],
    router: ['containerColor=RouterCyan', 'Text(uiMessageZh(text)'],
    native: ['leadingIcon = Icons.Rounded.Tune', 'NativeFirmwareVersionRow', 'Text(uiMessageZh(text)', '次尝试'],
}
for path, needles in checks.items():
    data = read(path)
    for needle in needles:
        if needle not in data:
            raise RuntimeError(f"{path}: final marker missing: {needle!r}")

print("build185 expressive UI polish applied")
