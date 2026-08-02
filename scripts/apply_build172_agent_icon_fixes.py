#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one old pattern, found {count}")
    return text.replace(old, new, 1)


def matching_brace(text: str, open_index: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    for index in range(open_index, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError("unbalanced Kotlin onClick block")


def replace_click_before_label(section: str, label: str, expression: str) -> str:
    label_index = section.find(f'Text("{label}"')
    if label_index < 0:
        raise RuntimeError(f"button label missing: {label}")
    click_index = section.rfind("onClick = {", 0, label_index)
    if click_index < 0:
        raise RuntimeError(f"onClick missing before: {label}")
    open_index = section.find("{", click_index)
    close_index = matching_brace(section, open_index)
    return section[:click_index] + f"onClick = {{ {expression} }}" + section[close_index + 1 :]


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '"v$NAME build$CODE · 图标与设备数据回归修复" to listOf(',
        '"v$NAME build$CODE · Relay 更新与启动图标修复" to listOf(',
        "build172 changelog title",
    )
    text = replace_once(
        text,
        '                "启动图标加入自适应安全边距，顶部和两侧网络节点完整显示",',
        '                "启动图标拆分为全幅渐变背景和透明网络前景，清除四周脏边",\n'
        '                "Relay 版本检查与更新任务移到应用级作用域，离开页面仍会继续",\n'
        '                "更新后持续监测 Agent 心跳，版本号和最后上报时间自动刷新",',
        "build172 changelog items",
    )

    text = replace_once(
        text,
        '''        val prefs = AppPrefs(this)
        // Preload the shared router-control repository before any settings page''',
        '''        val prefs = AppPrefs(this)
        AgentUpdateCoordinator.bind(prefs)
        AgentUpdateCoordinator.check(prefs, silent = true)
        // Preload the shared router-control repository before any settings page''',
        "application startup Relay monitoring",
    )

    old_state = '''    var agentInfo by remember { mutableStateOf(storedAgentUpdateInfo(prefs.agentUpdateInfoJson)) }
    var agentMessage by remember { mutableStateOf(prefs.agentUpdateMessage.ifBlank { "等待检查 Rust Agent 版本" }) }
    var cleanupMessage by remember { mutableStateOf("可清理所有 Agent 备份和非必要临时日志") }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var agentBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()'''
    new_state = '''    AgentUpdateCoordinator.bind(prefs)
    val agentUpdateUi by AgentUpdateCoordinator.state.collectAsState()
    val agentInfo = agentUpdateUi.info
    val agentMessage = agentUpdateUi.message
    var cleanupMessage by remember { mutableStateOf("可清理所有 Agent 备份和非必要临时日志") }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var cleanupBusy by remember { mutableStateOf(false) }
    val agentBusy = agentUpdateUi.busy || cleanupBusy
    val scope = rememberCoroutineScope()'''
    text = replace_once(text, old_state, new_state, "application-level Agent update state")

    cleanup_start = text.find("    if (showCleanupConfirm) {")
    cleanup_end = text.find("    Box(Modifier.fillMaxWidth().height(196.dp)", cleanup_start)
    if cleanup_start < 0 or cleanup_end < 0:
        raise RuntimeError("Agent cleanup dialog boundaries missing")
    cleanup_section = text[cleanup_start:cleanup_end]
    if "agentBusy = true" in cleanup_section:
        if cleanup_section.count("agentBusy = true") != 1 or cleanup_section.count("agentBusy = false") != 1:
            raise RuntimeError("unexpected cleanup busy assignments")
        cleanup_section = cleanup_section.replace("agentBusy = true", "cleanupBusy = true", 1)
        cleanup_section = cleanup_section.replace("agentBusy = false", "cleanupBusy = false", 1)
        text = text[:cleanup_start] + cleanup_section + text[cleanup_end:]
    elif "cleanupBusy = true" not in cleanup_section or "cleanupBusy = false" not in cleanup_section:
        raise RuntimeError("cleanup busy state migration missing")

    start = text.find('        title = "Rust Agent 更新",')
    end = text.find('        OutlinedButton(\n            onClick = { showCleanupConfirm = true }', start)
    if start < 0 or end < 0:
        raise RuntimeError("Rust Agent update card boundaries missing")
    section = text[start:end]
    section = replace_click_before_label(section, "检查更新", "AgentUpdateCoordinator.check(prefs)")
    section = replace_click_before_label(section, "立即更新", "AgentUpdateCoordinator.update(prefs)")
    text = text[:start] + section + text[end:]

    update_section = text[start:text.find(
        '        OutlinedButton(\n            onClick = { showCleanupConfirm = true }',
        start,
    )]
    forbidden = (
        "下发失败：${it.message}",
        "api.requestAgentUpdateCheck()",
        "api.requestAgentUpdate()",
        "agentInfo = AgentUpdateInfo(",
        "agentMessage = updateMessage",
    )
    leftovers = [value for value in forbidden if value in update_section]
    if leftovers:
        raise RuntimeError(f"old composition-bound Agent update logic remains: {leftovers}")

    MAIN.write_text(text, encoding="utf-8")


def verify() -> None:
    text = MAIN.read_text(encoding="utf-8")
    gradle = GRADLE.read_text(encoding="utf-8")
    code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if code_match is None or int(code_match.group(1)) < 172:
        raise RuntimeError("build172 migration requires versionCode >= 172")
    required = (
        'Relay 更新与启动图标修复',
        'AgentUpdateCoordinator.bind(prefs)',
        'AgentUpdateCoordinator.check(prefs, silent = true)',
        'AgentUpdateCoordinator.state.collectAsState()',
        'var cleanupBusy by remember',
        'val agentBusy = agentUpdateUi.busy || cleanupBusy',
        'cleanupBusy = true',
        'cleanupBusy = false',
        'onClick = { AgentUpdateCoordinator.check(prefs) }',
        'onClick = { AgentUpdateCoordinator.update(prefs) }',
    )
    combined = gradle + "\n" + text
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build172 verification failed: {missing}")


def main() -> None:
    patch_main()
    verify()
    print("build172 Relay update coordinator and launcher source state applied")


if __name__ == "__main__":
    main()
