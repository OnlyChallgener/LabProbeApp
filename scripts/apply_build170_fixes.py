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
    text = replace_once(
        text,
        'suspend fun sshExec(host: String, port: Int, user: String, pass: String, cmd: String): String = withContext(Dispatchers.IO) {',
        'suspend fun sshExec(context: Context, host: String, port: Int, user: String, pass: String, cmd: String): String = withContext(Dispatchers.IO) {',
        "SSH function signature",
    )
    text = replace_once(
        text,
        '    val session = JSch().getSession(user, host, port); session.setPassword(pass)',
        '    val jsch = JSch()\n'
        '    val knownHosts = File(context.filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }\n'
        '    jsch.setKnownHosts(knownHosts.absolutePath)\n'
        '    val session = jsch.getSession(user, host, port); session.setPassword(pass)',
        "SSH known hosts",
    )
    old_cfg = '    val cfg = java.util.Properties(); cfg["StrictHostKeyChecking"]="no"; cfg["PreferredAuthentications"]="password,keyboard-interactive,publickey"; cfg["server_host_key"]="ssh-rsa,rsa-sha2-256,rsa-sha2-512,ssh-ed25519,ecdsa-sha2-nistp256"; cfg["PubkeyAcceptedAlgorithms"]="+ssh-rsa,rsa-sha2-256,rsa-sha2-512"; cfg["kex"]="curve25519-sha256@libssh.org,curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1"; cfg["cipher.s2c"]="aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"; cfg["cipher.c2s"]="aes256-ctr,aes128-ctr,aes192-ctr,aes128-cbc,3des-cbc"; cfg["mac.s2c"]="hmac-sha2-256,hmac-sha2-512,hmac-sha1"; cfg["mac.c2s"]="hmac-sha2-256,hmac-sha2-512,hmac-sha1"; cfg["enable_server_sig_algs"]="yes"; session.setConfig(cfg)'
    new_cfg = '    val cfg = java.util.Properties(); cfg["StrictHostKeyChecking"]="ask"; cfg["PreferredAuthentications"]="password,keyboard-interactive,publickey"; cfg["server_host_key"]="rsa-sha2-256,rsa-sha2-512,ssh-ed25519,ecdsa-sha2-nistp256,ssh-rsa"; cfg["PubkeyAcceptedAlgorithms"]="rsa-sha2-256,rsa-sha2-512,ssh-rsa"; cfg["kex"]="curve25519-sha256@libssh.org,curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256"; cfg["cipher.s2c"]="aes256-ctr,aes192-ctr,aes128-ctr"; cfg["cipher.c2s"]="aes256-ctr,aes192-ctr,aes128-ctr"; cfg["mac.s2c"]="hmac-sha2-256,hmac-sha2-512"; cfg["mac.c2s"]="hmac-sha2-256,hmac-sha2-512"; cfg["enable_server_sig_algs"]="yes"; session.setConfig(cfg)'
    text = replace_once(text, old_cfg, new_cfg, "SSH secure algorithms")
    text = replace_once(
        text,
        'override fun promptYesNo(message:String?)=true',
        'override fun promptYesNo(message:String?) = message?.contains("HOST IDENTIFICATION HAS CHANGED", ignoreCase = true) != true',
        "SSH changed-host rejection",
    )
    if any(marker in text for marker in ("diffie-hellman-group1-sha1", "3des-cbc", "aes128-cbc", "hmac-sha1", 'StrictHostKeyChecking"]="no"')):
        raise RuntimeError("weak SSH compatibility algorithms remain")

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
