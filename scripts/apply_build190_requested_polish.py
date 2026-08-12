#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
PORT = ROOT / "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"
NATIVE = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterNativeToolsUi.kt"
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
    next_fun = text.find("\n@Composable", start + len(marker))
    scope_end = next_fun if next_fun >= 0 else len(text)
    scope = text[start:scope_end]
    if new in scope:
        return text
    count = scope.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one old form in scope, found {count}")
    pos = text.find(old, start, scope_end)
    return text[:pos] + new + text[pos + len(old):]


# 1) Mapping detail: only the right-side values in the primary detail card go down one type step.
port = PORT.read_text(encoding="utf-8")
old_detail_block = '''            LabCoreCard(compact = true) {
                PortMapDetailLine("状态", portMapStatus(rule).text, portMapStatus(rule).color)
                PortMapDetailLine("期望 / 同步", "${portMapDesiredText(rule)} · ${portMapSyncText(rule)}")
                PortMapDetailLine("监听", "[::]:${rule.listenPort}", copyable = true)
                PortMapDetailLine("配置目标", rule.targetText, copyable = true)
                if (rule.runtime.resolvedTarget.isNotBlank()) PortMapDetailLine("实际目标", rule.runtime.resolvedTarget, PortBlue, copyable = true)
                PortMapDetailLine("运行时间", portMapRunningText(rule))
                PortMapDetailLine("剩余时间", portMapRemainingText(rule))
                PortMapDetailLine("启动有效期", if (rule.leaseSeconds > 0) "每次启动 ${formatPortDuration(rule.leaseSeconds)}" else "永久")
                PortMapDetailLine("最近解析", formatEpoch(rule.runtime.lastResolvedAt))
                if (rule.revision > 0L) PortMapDetailLine("配置版本", "revision ${rule.revision}")
            }'''
new_detail_block = '''            LabCoreCard(compact = true) {
                PortMapDetailLine("状态", portMapStatus(rule).text, portMapStatus(rule).color, compactValue = true)
                PortMapDetailLine("期望 / 同步", "${portMapDesiredText(rule)} · ${portMapSyncText(rule)}", compactValue = true)
                PortMapDetailLine("监听", "[::]:${rule.listenPort}", copyable = true, compactValue = true)
                PortMapDetailLine("配置目标", rule.targetText, copyable = true, compactValue = true)
                if (rule.runtime.resolvedTarget.isNotBlank()) PortMapDetailLine("实际目标", rule.runtime.resolvedTarget, PortBlue, copyable = true, compactValue = true)
                PortMapDetailLine("运行时间", portMapRunningText(rule), compactValue = true)
                PortMapDetailLine("剩余时间", portMapRemainingText(rule), compactValue = true)
                PortMapDetailLine("启动有效期", if (rule.leaseSeconds > 0) "每次启动 ${formatPortDuration(rule.leaseSeconds)}" else "永久", compactValue = true)
                PortMapDetailLine("最近解析", formatEpoch(rule.runtime.lastResolvedAt), compactValue = true)
                if (rule.revision > 0L) PortMapDetailLine("配置版本", "revision ${rule.revision}", compactValue = true)
            }'''
port = replace_once(port, old_detail_block, new_detail_block, "mapping primary detail values")
port = replace_once(
    port,
    'private fun PortMapDetailLine(label: String, value: String, color: Color = LabV2.Ink, copyable: Boolean = false) {\n    val context = LocalContext.current',
    'private fun PortMapDetailLine(label: String, value: String, color: Color = LabV2.Ink, copyable: Boolean = false, compactValue: Boolean = false) {\n    val context = LocalContext.current\n    val valueStyle = if (compactValue) LabTypography.Body else LabTypography.Value',
    "mapping detail compact style parameter",
)
port = replace_after(
    port,
    'private fun PortMapDetailLine(',
    'fontSize = LabTypography.Value.fontSize, lineHeight = LabTypography.Value.lineHeight, color = color, fontWeight = FontWeight.SemiBold, softWrap = true',
    'fontSize = valueStyle.fontSize, lineHeight = valueStyle.lineHeight, color = color, fontWeight = FontWeight.SemiBold, softWrap = true',
    "mapping copyable value size",
)
port = replace_after(
    port,
    'private fun PortMapDetailLine(',
    'fontSize = LabTypography.Value.fontSize, lineHeight = LabTypography.Value.lineHeight, color = color, fontWeight = FontWeight.SemiBold, maxLines = 3',
    'fontSize = valueStyle.fontSize, lineHeight = valueStyle.lineHeight, color = color, fontWeight = FontWeight.SemiBold, maxLines = 3',
    "mapping plain value size",
)
PORT.write_text(port, encoding="utf-8")

# 2) Events: the visible gap came from EventCompactCard's 68dp outer minimum height,
# not the LazyColumn spacing. Reduce that minimum so the cards actually sit closer.
main = MAIN.read_text(encoding="utf-8")
main = replace_after(
    main,
    'private fun EventCompactCard(',
    'Box(Modifier.fillMaxWidth().heightIn(min = 68.dp)) {',
    'Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {',
    "event compact card effective spacing",
)

# 4) Home: only reduce row-to-row spacing inside the two named cards.
main = replace_after(
    main,
    'fun HealthExitCard(',
    'if (index > 0) Spacer(Modifier.height(5.dp))',
    'if (index > 0) Spacer(Modifier.height(3.dp))',
    "exit card row spacing",
)
main = replace_after(
    main,
    'fun HealthVpnCard(',
    'if (idx != rows.lastIndex) Spacer(Modifier.height(9.dp))',
    'if (idx != rows.lastIndex) Spacer(Modifier.height(6.dp))',
    "vpn card row spacing",
)
MAIN.write_text(main, encoding="utf-8")

# 3) NAT: only the previously requested content text goes one step smaller and solid dark.
native = NATIVE.read_text(encoding="utf-8")
native = replace_after(
    native,
    'NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)',
    'fontSize = LabTypography.Value.fontSize,\n                            lineHeight = LabTypography.Value.lineHeight,',
    'fontSize = LabTypography.Body.fontSize,\n                            lineHeight = LabTypography.Body.lineHeight,',
    "recent detection title size",
)
native = replace_after(
    native,
    'NativeTitle(Icons.Rounded.History, "最近检测", NativeAmber)',
    'style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)',
    'style = LabTypography.Body.copy(color = NativeInk, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)',
    "recent detection body size/color",
)
native = replace_after(
    native,
    'private fun NativeAnalysisValueRow(label: String, value: String)',
    'style = LabTypography.Value.copy(color = NativeMuted, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),',
    'style = LabTypography.Body.copy(color = NativeInk, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),',
    "analysis value size/color",
)
NATIVE.write_text(native, encoding="utf-8")

# New installable build over build189.
gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, 'versionCode = 189', 'versionCode = 190', "version code")
GRADLE.write_text(gradle, encoding="utf-8")

print("build190 requested-only polish applied")
