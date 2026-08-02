#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


def patch_main() -> None:
    path = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '"v$NAME build$CODE · 图标与终端状态修复" to listOf(',
        '"v$NAME build$CODE · 数据一致性与安全修复" to listOf(',
        "changelog title",
    )
    text = replace_once(
        text,
        '                "启动图标移除背景发光圆环，保留原有青绿渐变与网络立方体",',
        '                "启动图标替换为新版青绿网络节点 Logo，并同步自适应圆形图标",\n'
        '                "关注与离线列表统一按 MAC 使用同一份最新设备记录",\n'
        '                "永久 IPv6 映射保留真实启动时间，缺失时不再显示 0 分",\n'
        '                "SSH 密码迁移至 Android Keystore，并启用主机密钥校验",',
        "changelog items",
    )

    text = replace_once(
        text,
        '    private val secureTokenStore = SecureTokenStore(context)\n',
        '    private val secureTokenStore = SecureTokenStore(context)\n'
        '    private val secureSshPasswordStore = SecureSshPasswordStore(context)\n',
        "secure ssh store field",
    )
    text = replace_once(
        text,
        '        if (sp.contains("token")) sp.edit().remove("token").apply()\n'
        '    }',
        '        if (sp.contains("token")) sp.edit().remove("token").apply()\n'
        '        val legacySshPassword = sp.getString("ssh_password", "").orEmpty()\n'
        '        if (secureSshPasswordStore.get().isBlank() && legacySshPassword.isNotBlank()) {\n'
        '            secureSshPasswordStore.set(legacySshPassword)\n'
        '        }\n'
        '        if (sp.contains("ssh_password")) sp.edit().remove("ssh_password").apply()\n'
        '    }',
        "legacy ssh password migration",
    )
    text = replace_once(
        text,
        '    var sshPassword: String get() = sp.getString("ssh_password", "") ?: ""\n'
        '        set(v) = sp.edit().putString("ssh_password", v).apply()',
        '    var sshPassword: String get() = secureSshPasswordStore.get()\n'
        '        set(v) { secureSshPasswordStore.set(v); if (sp.contains("ssh_password")) sp.edit().remove("ssh_password").apply() }',
        "encrypted ssh password property",
    )

    text = replace_once(
        text,
        'fun HealthDevicesCard(state: AppState, onClick: () -> Unit = {}) {\n'
        '    val visibleDevices = remember(state.devices) { followedDeviceList(state.devices).take(4) }',
        'fun HealthDevicesCard(state: AppState, onClick: () -> Unit = {}) {\n'
        '    val visibleDevices = remember(state.devices, state.onlineDevices, state.offlineDevices) {\n'
        '        followedDeviceList(mergeSharedDeviceState(state.offlineDevices + state.devices, state.onlineDevices)).take(4)\n'
        '    }',
        "home watched canonical devices",
    )
    text = replace_once(
        text,
        '    val list = when (mode) {\n'
        '        "online" -> state.onlineDevices\n'
        '        "offline" -> state.offlineDevices\n'
        '        else -> followed\n'
        '    }',
        '    val list = when (mode) {\n'
        '        "online" -> shared.filter { it.online }\n'
        '        "offline" -> shared.filterNot { it.online }\n'
        '        else -> followed\n'
        '    }',
        "device tabs canonical list",
    )

    text = replace_once(
        text,
        '        if (prefs.hub.isBlank()) throw RuntimeException("Hub 地址为空，请先输入")\n'
        '        val requestBuilder = Request.Builder()\n'
        '            .url(joinUrl(prefs.hub, path))',
        '        if (prefs.hub.isBlank()) throw RuntimeException("Hub 地址为空，请先输入")\n'
        '        val safeHub = validateHubTransportAddress(prefs.hub)\n'
        '        val requestBuilder = Request.Builder()\n'
        '            .url(joinUrl(safeHub, path))',
        "Hub cleartext transport guard",
    )

    text = replace_once(
        text,
        'sshExec(host, port.toIntOrNull() ?: 22, user, password, command)',
        'sshExec(ctx, host, port.toIntOrNull() ?: 22, user, password, command)',
        "SSH context call",
    )
    signature_patterns = [
        (
            'suspend fun sshExec(host: String, port: Int, user: String, password: String, command: String): String = withContext(Dispatchers.IO) {',
            'suspend fun sshExec(context: Context, host: String, port: Int, user: String, password: String, command: String): String = withContext(Dispatchers.IO) {',
        ),
        (
            'suspend fun sshExec(host: String, port: Int, user: String, pass: String, command: String): String = withContext(Dispatchers.IO) {',
            'suspend fun sshExec(context: Context, host: String, port: Int, user: String, pass: String, command: String): String = withContext(Dispatchers.IO) {',
        ),
    ]
    if not any(new in text for _, new in signature_patterns):
        for old, new in signature_patterns:
            if old in text:
                text = text.replace(old, new, 1)
                break
        else:
            raise RuntimeError("SSH function signature pattern missing")

    if 'ssh_known_hosts' not in text:
        marker = '        val jsch = JSch()\n'
        if marker not in text:
            marker = '    val jsch = JSch()\n'
        if marker not in text:
            raise RuntimeError("JSch construction pattern missing")
        indent = marker.split('val jsch')[0]
        text = text.replace(
            marker,
            marker
            + indent + 'val knownHosts = File(context.filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }\n'
            + indent + 'jsch.setKnownHosts(knownHosts.absolutePath)\n',
            1,
        )
    text = text.replace('setConfig("StrictHostKeyChecking", "no")', 'setConfig("StrictHostKeyChecking", "accept-new")')
    text = text.replace('setConfig("StrictHostKeyChecking", "ask")', 'setConfig("StrictHostKeyChecking", "accept-new")')
    weak_markers = (
        "diffie-hellman-group1-sha1",
        "3des-cbc",
        "aes128-cbc",
        "hmac-sha1",
        "ssh-dss",
    )
    text = "\n".join(
        line
        for line in text.splitlines()
        if not ("setConfig" in line and any(marker in line for marker in weak_markers))
    ) + "\n"
    if 'StrictHostKeyChecking", "accept-new"' not in text:
        raise RuntimeError("SSH strict host checking was not installed")

    path.write_text(text, encoding="utf-8")


def patch_secure_store() -> None:
    path = ROOT / "app/src/main/kotlin/com/labprobe/app/SecureTokenStore.kt"
    text = path.read_text(encoding="utf-8")
    if "class SecureSshPasswordStore" not in text:
        marker = "/** Removes the deprecated APP-side HOOK_TOKEN copy left by build132. */"
        addition = '''/** Stores an optional SSH password with a separate non-exportable key. */
class SecureSshPasswordStore(context: Context) {
    private val delegate = SecureStringStore(context, "labprobe_secure_ssh", "labprobe_ssh_password_v1")
    fun get(): String = delegate.get()
    fun set(value: String) = delegate.set(value)
}

'''
        text = replace_once(text, marker, addition + marker, "secure SSH store class")
    path.write_text(text, encoding="utf-8")


def patch_ipv6() -> None:
    path = ROOT / "app/src/main/kotlin/com/labprobe/app/Ipv6Utils.kt"
    text = path.read_text(encoding="utf-8")
    old = '''            val preferred = when {
                oldRejected && ipv6StateRank(candidate.state) > 1 -> candidate
                candidateRejected && ipv6StateRank(old.state) > 1 -> old
                oldRejected -> old
                candidateRejected -> candidate
                oldHasMetadata && !candidateHasMetadata -> old
                candidateHasMetadata && !oldHasMetadata -> candidate
                scoreIpv6Candidate(candidate) > scoreIpv6Candidate(old) -> candidate
                else -> old
            }'''
    new = '''            val preferred = when {
                oldRejected && !candidateRejected -> candidate
                candidateRejected && !oldRejected -> old
                oldRejected && candidateRejected -> if (ipv6StateRank(candidate.state) > ipv6StateRank(old.state)) candidate else old
                oldHasMetadata && !candidateHasMetadata -> old
                candidateHasMetadata && !oldHasMetadata -> candidate
                scoreIpv6Candidate(candidate) > scoreIpv6Candidate(old) -> candidate
                else -> old
            }'''
    text = replace_once(text, old, new, "IPv6 rejected-state merge")
    path.write_text(text, encoding="utf-8")


def patch_repository() -> None:
    path = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterRepository.kt"
    text = path.read_text(encoding="utf-8")
    if "import kotlinx.coroutines.cancel" not in text:
        text = replace_once(
            text,
            "import kotlinx.coroutines.async\n",
            "import kotlinx.coroutines.async\nimport kotlinx.coroutines.cancel\n",
            "repository cancel import",
        )
    if "fun close()" not in text:
        marker = "\n}\n\nobject RouterRepositoryRegistry"
        addition = '''

    fun close() {
        scope.cancel()
        commandScope.cancel()
    }
'''
        text = replace_once(
            text,
            marker,
            addition + "\n}\n\nobject RouterRepositoryRegistry",
            "repository close",
        )
    old = '''        if (instance == null || key != next) {
            key = next
            instance = RouterRepository(prefs)
        }'''
    new = '''        if (instance == null || key != next) {
            val previous = instance
            key = next
            instance = RouterRepository(prefs)
            previous?.close()
        }'''
    text = replace_once(text, old, new, "repository registry replacement")
    path.write_text(text, encoding="utf-8")


def patch_port_mapping() -> None:
    path = ROOT / "app/src/main/kotlin/com/labprobe/app/PortMapping.kt"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '                PortMapDetailLine("运行时间", formatPortDuration(portMapRunningDuration(rule)))',
        '                PortMapDetailLine("运行时间", portMapRunningText(rule))',
        "port map detail running text",
    )
    text = replace_once(
        text,
        '    rule.effectiveActualState == "running" -> "已运行 ${formatPortDuration(portMapRunningDuration(rule))} · 剩余 ${portMapRemainingText(rule)}"',
        '    rule.effectiveActualState == "running" -> "${portMapRunningText(rule)} · 剩余 ${portMapRemainingText(rule)}"',
        "port map card running text",
    )
    if "private fun portMapRunningText" not in text:
        marker = "\nprivate fun portMapDesiredText(rule: PortMapRule): String"
        helper = '''
private fun portMapRunningText(rule: PortMapRule): String {
    val duration = portMapRunningDuration(rule)
    return if (duration == null) "运行时间同步中" else "已运行 ${formatPortDuration(duration)}"
}
'''
        text = replace_once(text, marker, helper + marker, "port map running helper")
    path.write_text(text, encoding="utf-8")


def main() -> None:
    patch_main()
    patch_secure_store()
    patch_ipv6()
    patch_repository()
    patch_port_mapping()
    print("build170 APP fixes applied")


if __name__ == "__main__":
    main()
