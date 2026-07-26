#!/usr/bin/env python3
"""Build159: reliable router commands, cache-first refresh and full-width NAT action."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
REPOSITORY = SRC / "RouterRepository.kt"
UI = SRC / "RouterControlUi.kt"
NAT_UI = SRC / "RouterNativeToolsUi.kt"
MAIN = SRC / "MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build159 anchor: {label}")
    return text.replace(old, new, 1)


def apply() -> None:
    repository = REPOSITORY.read_text(encoding="utf-8")
    repository = replace_once(
        repository,
        "import kotlinx.coroutines.CoroutineScope\n",
        "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CoroutineScope\n",
        "CancellationException import",
    )
    repository = replace_once(
        repository,
        "    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)\n",
        "    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)\n"
        "    val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)\n",
        "repository-owned command scope",
    )
    message_block = '''    private fun message(error: Throwable, fallback: String): String = when {
        error.message.isNullOrBlank() -> fallback
        error.message!!.contains("timeout", true) || error.message!!.contains("timed out", true) -> "后台同步较慢，已保留上次数据"
        else -> error.message!!
    }
'''
    repository = replace_once(
        repository,
        message_block,
        message_block + '''
    private suspend fun <T> executeCommand(block: suspend () -> T): Result<T> = try {
        Result.success(withTimeout(25_000L) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
''',
        "command execution helper",
    )
    repository = repository.replace(
        "runCatching { withTimeout(12_000L) { api.setUpnp(enabled, wan) } }",
        "executeCommand { api.setUpnp(enabled, wan) }",
    )
    repository = repository.replace(
        "runCatching { withTimeout(12_000L) { block() } }",
        "executeCommand { block() }",
    )
    if "withTimeout(12_000L)" in repository:
        raise RuntimeError("old 12-second router command timeout remains")
    REPOSITORY.write_text(repository, encoding="utf-8")

    ui = UI.read_text(encoding="utf-8")
    scope_pattern = re.compile(
        r'(val repository = remember\(prefs\.hub, prefs\.token, prefs\.hubDns\) '
        r'\{ RouterRepositoryRegistry\.get\(prefs\) \}\n(?:.*\n){0,5}?)'
        r'    val scope = rememberCoroutineScope\(\)'
    )
    ui, replaced_scopes = scope_pattern.subn(r'\1    val scope = repository.commandScope', ui)
    if replaced_scopes < 4:
        raise RuntimeError(f"only replaced {replaced_scopes} router repository page scopes")

    for resource_name in ("PortMappings", "Upnp", "Firewall", "Ddns"):
        ui = ui.replace(
            f"repository.refresh{resource_name}(true)",
            f"repository.refresh{resource_name}(false)",
        )
    forbidden_force = (
        "repository.refreshPortMappings(true)",
        "repository.refreshUpnp(true)",
        "repository.refreshFirewall(true)",
        "repository.refreshDdns(true)",
    )
    if any(value in ui for value in forbidden_force):
        raise RuntimeError("a user refresh still bypasses the Hub cache")

    ui = ui.replace(
        'if (error.isNotBlank()) item { CompactMessage(error, RouterAmber) }\n        if (resource.value == null)',
        'if (error.isNotBlank()) item { CompactMessage(error, RouterAmber) }\n'
        '        if (resource.mutating) item { CompactMessage("设置正在后台应用，页面可以安全退出", RouterBlue) }\n'
        '        if (resource.value == null)',
    )
    ui = ui.replace(
        'if(error.isNotBlank())CompactMessage(error,RouterAmber)\n    if(resource.value==null)',
        'if(error.isNotBlank())CompactMessage(error,RouterAmber)\n'
        '    if(resource.mutating)CompactMessage("设置正在后台应用，页面可以安全退出",RouterBlue)\n'
        '    if(resource.value==null)',
    )
    UI.write_text(ui, encoding="utf-8")

    nat_ui = NAT_UI.read_text(encoding="utf-8")
    nat_ui = replace_once(
        nat_ui,
        '''            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
Spacer(Modifier.width(8.dp))
                Button(''',
        '''            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(''',
        "NAT action row",
    )
    nat_ui = replace_once(
        nat_ui,
        "modifier = Modifier.width(158.dp).height(44.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),",
        "modifier = Modifier.fillMaxWidth().height(44.dp).nativeBlueShadow(RoundedCornerShape(14.dp), 7.dp),",
        "full-width NAT start button",
    )
    NAT_UI.write_text(nat_ui, encoding="utf-8")

    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = gradle.replace("versionCode = 158", "versionCode = 159")
    gradle = gradle.replace('versionName = "0.10.16"', 'versionName = "0.10.17"')
    GRADLE.write_text(gradle, encoding="utf-8")

    main = MAIN.read_text(encoding="utf-8")
    main = main.replace(
        '"v$NAME build$CODE · 统一路由数据源与无感预加载"',
        '"v$NAME build$CODE · 路由控制队列与可靠指令"',
    )
    MAIN.write_text(main, encoding="utf-8")

    combined = REPOSITORY.read_text(encoding="utf-8") + UI.read_text(encoding="utf-8") + NAT_UI.read_text(encoding="utf-8")
    required = (
        "val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)",
        "executeCommand { api.setUpnp(enabled, wan) }",
        "withTimeout(25_000L)",
        "repository.refreshUpnp(false)",
        "设置正在后台应用，页面可以安全退出",
        "modifier = Modifier.fillMaxWidth().height(44.dp).nativeBlueShadow",
    )
    missing = [value for value in required if value not in combined]
    if missing:
        raise RuntimeError(f"build159 verification failed: {missing}")
    print("build159 reliable router commands, cache-first refresh and full-width NAT button applied")


if __name__ == "__main__":
    apply()
