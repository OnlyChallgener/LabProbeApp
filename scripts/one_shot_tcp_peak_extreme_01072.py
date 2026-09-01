#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing expected snippet in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


models = "app/src/main/kotlin/com/labprobe/app/TcpPeakModels.kt"
ui = "app/src/main/kotlin/com/labprobe/app/TcpPeakConnections.kt"
main = "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
test = "app/src/test/kotlin/com/labprobe/app/TcpPeakModelsTest.kt"
gradle = "app/build.gradle.kts"

replace_once(
    models,
    "    val cps: Int = 500,\n    val connectTimeoutMs: Int = 1_500,",
    "    val cps: Int = 500,\n    val extremeMode: Boolean = false,\n    val connectTimeoutMs: Int = 1_500,",
)
replace_once(
    models,
    "        cps = cps.coerceIn(1, 2_000),\n        connectTimeoutMs =",
    "        cps = cps.coerceIn(1, 10_000),\n        extremeMode = extremeMode && side == TcpPeakSide.RELAY,\n        connectTimeoutMs =",
)
replace_once(
    models,
    '        cps !in 1..2_000 -> "CPS 必须是 1–2000"',
    '        cps !in 1..10_000 -> "CPS 必须是 1–10000"\n        extremeMode && side != TcpPeakSide.RELAY -> "极限模式仅支持 Relay 宿主机"',
)
replace_once(
    models,
    '        .put("cps", config.cps)\n        .put("connectTimeoutMs",',
    '        .put("cps", config.cps)\n        .put("extremeMode", config.extremeMode)\n        .put("connectTimeoutMs",',
)
replace_once(
    models,
    "                cps = row.optInt(\"cps\"),\n                connectTimeoutMs =",
    "                cps = row.optInt(\"cps\"),\n                extremeMode = row.optBoolean(\"extremeMode\", false),\n                connectTimeoutMs =",
)

replace_once(
    main,
    '            .put("cps", value.cps)\n            .put("connectTimeoutMs", value.connectTimeoutMs)',
    '            .put("cps", value.cps)\n            .put("extremeMode", value.extremeMode)\n            .put("connectTimeoutMs", value.connectTimeoutMs)',
)

replace_once(
    ui,
    '    var cps by remember { mutableStateOf(pendingAiCommand?.config?.cps?.toString() ?: prefs.tcpPeakCps) }\n    var logsExpanded',
    '    var cps by remember { mutableStateOf(pendingAiCommand?.config?.cps?.toString() ?: prefs.tcpPeakCps) }\n    var extremeMode by remember { mutableStateOf(pendingAiCommand?.config?.extremeMode ?: false) }\n    var logsExpanded',
)
replace_once(
    ui,
    '''        targetConnections = target.toIntOrNull() ?: 0,
        cps = cps.toIntOrNull() ?: 0
    )
''',
    '''        targetConnections = target.toIntOrNull() ?: 0,
        cps = cps.toIntOrNull() ?: 0,
        extremeMode = side == TcpPeakSide.RELAY && extremeMode
    )
''',
)
replace_once(
    ui,
    '''            target = target,
            cps = cps,
            enabled = !active,
            onSide = { side = it },
''',
    '''            target = target,
            cps = cps,
            extremeMode = extremeMode,
            enabled = !active,
            onSide = {
                side = it
                if (it != TcpPeakSide.RELAY) extremeMode = false
            },
''',
)
replace_once(
    ui,
    '''            onTarget = { target = it; prefs.tcpPeakTarget = it },
            onCps = { cps = it; prefs.tcpPeakCps = it }
        )
''',
    '''            onTarget = { target = it; prefs.tcpPeakTarget = it },
            onCps = { cps = it; prefs.tcpPeakCps = it },
            onExtremeMode = { extremeMode = it }
        )
''',
)
replace_once(
    ui,
    '''    target: String,
    cps: String,
    enabled: Boolean,
''',
    '''    target: String,
    cps: String,
    extremeMode: Boolean,
    enabled: Boolean,
''',
)
replace_once(
    ui,
    '''    onTarget: (String) -> Unit,
    onCps: (String) -> Unit
) {
''',
    '''    onTarget: (String) -> Unit,
    onCps: (String) -> Unit,
    onExtremeMode: (Boolean) -> Unit
) {
''',
)

# Insert Relay-only Standard/Extreme selector immediately after the test-side row.
anchor = '''            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("目标域名或 IP", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
'''
mode_ui = '''            if (side == TcpPeakSide.RELAY) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("测试模式", style = LabTypography.FieldLabel.copy(color = LabV2.InkMuted))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to "标准", true to "极限").forEachIndexed { index, item ->
                            SegmentedButton(
                                modifier = Modifier.weight(1f),
                                shape = SegmentedButtonDefaults.itemShape(index, 2, LabV2.CompactCardShape),
                                colors = segmentColors,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                                icon = {},
                                selected = extremeMode == item.first,
                                onClick = { onExtremeMode(item.first) },
                                enabled = enabled,
                                label = { Text(item.second, style = LabTypography.CompactButton) }
                            )
                        }
                    }
                    Text(
                        if (extremeMode) {
                            "极限模式会在 Relay 测试期间临时扩展源端口范围，结束、停止或异常后自动恢复；CPU、FD、Conntrack 和内存保护仍生效。"
                        } else {
                            "标准模式不修改 Relay 系统源端口范围。"
                        },
                        style = LabTypography.Supporting.copy(color = if (extremeMode) LabV2.Amber else LabV2.InkMuted)
                    )
                }
            }
'''
p = Path(ui)
text = p.read_text(encoding="utf-8")
if anchor not in text:
    raise SystemExit("missing target-field anchor")
p.write_text(text.replace(anchor, mode_ui + anchor, 1), encoding="utf-8")

replace_once(
    ui,
    '                "65535 是量程上限，不代表设备必须达到；IPv4 使用 A 记录，IPv6 使用 AAAA 记录。",',
    '                "65535 是量程上限，不代表设备必须达到；Relay CPS 最高 10000；IPv4 使用 A 记录，IPv6 使用 AAAA 记录。",',
)

replace_once(
    test,
    '''            targetConnections = 99_999,
            cps = 9_999
        ).normalized()
''',
    '''            targetConnections = 99_999,
            cps = 99_999,
            extremeMode = true
        ).normalized()
''',
)
replace_once(test, "        assertEquals(2_000, config.cps)", "        assertEquals(10_000, config.cps)\n        assertTrue(config.extremeMode)")

# Make this APK identifiable from the previous 0.10.71/build226 test build.
replace_once(gradle, "        versionCode = 226\n        versionName = \"0.10.71\"", "        versionCode = 227\n        versionName = \"0.10.72\"")

print("TCP peak extreme-mode APP patch applied")
