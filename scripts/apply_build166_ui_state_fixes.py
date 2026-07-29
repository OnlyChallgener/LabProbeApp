#!/usr/bin/env python3
"""Build166: restore home visual consistency and truthful IPv6 mapping state."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/labprobe/app"
MAIN = SRC / "MainActivity.kt"
NATIVE = SRC / "RouterNativeToolsUi.kt"
PORTMAP = SRC / "PortMapping.kt"
GRADLE = ROOT / "app/build.gradle.kts"
VERIFIER = ROOT / "scripts/verify_build154_sources.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build166 anchor: {label}")
    return text.replace(old, new, 1)


def patch_main(text: str) -> str:
    old_score = '''                    Row(verticalAlignment = Alignment.Top) {
                        Text("网络健康得分", Modifier.weight(1f).padding(top = 2.dp), fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Black, color = LabV2.Ink, maxLines = 1)
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Surface(shape = RoundedCornerShape(99.dp), color = scoreColor.copy(alpha = .10f)) {
                                Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(13.dp), tint = scoreColor)
                                    Text(scoreLabel, fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = scoreColor)
                                }
                            }
                            Text(
                                message.replace("刷新成功：", "").ifBlank { lastRefresh.ifBlank { "等待同步" } },
                                fontSize = 8.8.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LabV2.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }'''
    new_score = '''                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "网络健康得分",
                            Modifier.weight(1f),
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = LabV2.Ink,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                        Spacer(Modifier.width(5.dp))
                        Surface(shape = RoundedCornerShape(99.dp), color = scoreColor.copy(alpha = .10f)) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(13.dp), tint = scoreColor)
                                Text(scoreLabel, fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = scoreColor)
                            }
                        }
                    }
                    Text(
                        message.replace("刷新成功：", "最后刷新 ").ifBlank { lastRefresh.ifBlank { "等待同步" } },
                        fontSize = 9.2.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LabV2.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )'''
    text = replace_once(text, old_score, new_score, "full network score title")

    text = replace_once(
        text,
        'HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", LabV2.Purple, Modifier.weight(1f))',
        'HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", Color(0xFF64748B), Modifier.weight(1f))',
        "quiet grey SSH shortcut",
    )

    text = replace_once(
        text,
        '''                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(30.dp)).clickable { onNavigate("devices") }
                        )''',
        '''                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate("devices") }
                        )''',
        "terminal card shared click path",
    )

    old_mini = '''fun HealthMiniCard(title: String, value: String, unit: String, icon: ImageVector, accent: Color, subtitle: String, modifier: Modifier = Modifier) {
    HealthCard(modifier, verticalPadding = 11.dp) {'''
    new_mini = '''fun HealthMiniCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    accent: Color,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(30.dp)
    val cardModifier = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier
    HealthCard(cardModifier, verticalPadding = 11.dp) {'''
    text = replace_once(text, old_mini, new_mini, "shared mini-card surface")

    old_nav = '''@Composable
fun OneUiTopNav(titles: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.92f),
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(5.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            titles.forEachIndexed { i, t ->
                val active = i == selected
                Surface(
                    onClick = { onSelect(i) },
                    shape = RoundedCornerShape(24.dp),
                    color = if (active) Color.White.copy(alpha = .98f) else Color.Transparent,
                    shadowElevation = if (active) 2.dp else 0.dp,
                    modifier = Modifier.height(40.dp).weight(1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icons[i],
                            contentDescription = t,
                            tint = if (active) Color(0xFF2D63D8) else Color(0xFF64748B),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
'''
    new_nav = '''@Composable
fun OneUiTopNav(titles: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    val techBlue = Color(0xFF2D63D8)
    Surface(
        color = Color.White.copy(alpha = 0.92f),
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(5.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            titles.forEachIndexed { i, t ->
                val active = i == selected
                val itemShape = RoundedCornerShape(24.dp)
                val itemModifier = Modifier
                    .height(40.dp)
                    .weight(1f)
                    .then(
                        if (active) Modifier.shadow(
                            elevation = 5.dp,
                            shape = itemShape,
                            clip = false,
                            ambientColor = techBlue.copy(alpha = .18f),
                            spotColor = techBlue.copy(alpha = .28f)
                        ) else Modifier
                    )
                Surface(
                    onClick = { onSelect(i) },
                    shape = itemShape,
                    color = if (active) Color(0xFFF8FBFF) else Color.Transparent,
                    shadowElevation = 0.dp,
                    border = if (active) androidx.compose.foundation.BorderStroke(1.dp, techBlue.copy(alpha = .14f)) else null,
                    modifier = itemModifier
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icons[i],
                            contentDescription = t,
                            tint = if (active) techBlue else Color(0xFF64748B),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
'''
    text = replace_once(text, old_nav, new_nav, "technology-blue selected navigation")

    old_changelog = '''            "v$NAME build$CODE · NAT 诊断、Agent 更新与映射界面修复" to listOf(
                "NAT 下拉菜单改为大圆角白色浮层，参数标题与内容拉开间距",'''
    new_changelog = '''            "v$NAME build$CODE · 首页视觉与映射状态修复" to listOf(
                "网络健康得分标题完整显示，刷新状态移到标题下一行",
                "SSH 小卡片改为浅灰色，终端在线与 DDNS 卡片统一白色层级",
                "主导航选中阴影统一为科技蓝，不再出现紫色",
                "IPv6 映射在线轮询实际状态，失联时不再误显示旧执行失败",
                "NAT 下拉菜单改为大圆角白色浮层，参数标题与内容拉开间距",'''
    text = replace_once(text, old_changelog, new_changelog, "build166 changelog")
    return text


def patch_native(text: str) -> str:
    return replace_once(
        text,
        '''        modifier = modifier.clickable(onClick = onClick)
    )''',
        '''        modifier = modifier,
        onClick = onClick
    )''',
        "DDNS shared mini-card click path",
    )


def patch_portmap(text: str) -> str:
    text = replace_once(
        text,
        '''    var loading by remember { mutableStateOf(PortMappingMemoryCache.agent == null && initialRules.isEmpty()) }
    var message''',
        '''    var loading by remember { mutableStateOf(PortMappingMemoryCache.agent == null && initialRules.isEmpty()) }
    var refreshInFlight by remember { mutableStateOf(false) }
    var message''',
        "portmap refresh guard",
    )

    old_refresh = '''    suspend fun refresh(silent: Boolean = false) {
        if (!silent) loading = true
        runCatching {
            val snapshot = api.list()
            val newAgent = snapshot.agent
            val explicitNewerEmpty = snapshot.rulesLoaded && snapshot.rules.isEmpty() && snapshot.rulesRevision > rulesRevision
            val mayAccept = snapshot.rules.isNotEmpty() || explicitNewerEmpty || (rules.isEmpty() && !persistentRules.hasDocument)
            if (mayAccept) {
                commitRulesLocally(snapshot.rules, snapshot.rulesRevision, snapshot.rulesUpdatedAt)
            }
            agent = newAgent
            PortMappingMemoryCache.agent = newAgent
            presenceStore.acceptHttp(newAgent)
            if (devices.isEmpty()) devices = deviceApi.getDevices(true)
            PortMappingMemoryCache.devices = devices
            message = if (!mayAccept && snapshot.rules.isEmpty() && rules.isNotEmpty()) {
                "Hub 本次未返回规则，已保留 APP 中的映射设置"
            } else ""
        }.onFailure {
            message = if (rules.isNotEmpty()) "映射状态暂未同步，已保留全部设置" else (it.message ?: "加载失败")
        }
        loading = false
    }

    LaunchedEffect(Unit) { refresh(silent = rules.isNotEmpty() || PortMappingMemoryCache.agent != null) }
    LaunchedEffect(liveAgent) {
        liveAgent?.let {
            agent = it
            PortMappingMemoryCache.agent = it
            loading = false
        }
    }
'''
    new_refresh = '''    suspend fun refresh(silent: Boolean = false) {
        if (refreshInFlight) return
        refreshInFlight = true
        if (!silent) loading = true
        try {
            val snapshot = kotlinx.coroutines.withTimeout(4_000L) { api.list() }
            val newAgent = snapshot.agent
            val explicitNewerEmpty = snapshot.rulesLoaded && snapshot.rules.isEmpty() && snapshot.rulesRevision > rulesRevision
            val mayAccept = snapshot.rules.isNotEmpty() || explicitNewerEmpty || (rules.isEmpty() && !persistentRules.hasDocument)
            if (mayAccept) {
                commitRulesLocally(snapshot.rules, snapshot.rulesRevision, snapshot.rulesUpdatedAt)
            }
            agent = newAgent
            PortMappingMemoryCache.agent = newAgent
            presenceStore.acceptHttp(newAgent)
            if (devices.isEmpty()) devices = runCatching { deviceApi.getDevices(true) }.getOrDefault(devices)
            PortMappingMemoryCache.devices = devices
            message = if (!mayAccept && snapshot.rules.isEmpty() && rules.isNotEmpty()) {
                "Hub 本次未返回规则，已保留 APP 中的映射设置"
            } else ""
        } catch (error: Throwable) {
            val agentKnownOnline = liveAgent?.online == true || agent.online
            if (rules.isNotEmpty() && agentKnownOnline) {
                rules = rules.map { it.copy(syncState = "stale") }
            }
            message = if (rules.isNotEmpty()) {
                if (agentKnownOnline) "Agent 在线，正在重新获取映射运行状态" else "映射状态暂未同步，已保留全部设置"
            } else (error.message ?: "加载失败")
        } finally {
            refreshInFlight = false
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh(silent = rules.isNotEmpty() || PortMappingMemoryCache.agent != null) }
    LaunchedEffect(liveAgent?.lastSeenAt) {
        liveAgent?.let {
            agent = it
            PortMappingMemoryCache.agent = it
            loading = false
            if (it.online) refresh(true)
        }
    }
    LaunchedEffect(agent.online) {
        while (true) {
            kotlinx.coroutines.delay(if (agent.online) 3_000L else 8_000L)
            refresh(true)
        }
    }
'''
    text = replace_once(text, old_refresh, new_refresh, "fresh portmap runtime polling")

    text = replace_once(
        text,
        'rule.syncState == "error" -> PortMapStatusUi("同步失败", PortRed)',
        'rule.syncState == "error" -> PortMapStatusUi("同步失败", PortRed)\n    rule.syncState == "stale" -> PortMapStatusUi("状态待同步", Color(0xFFF59E0B))',
        "stale portmap status",
    )
    text = replace_once(
        text,
        'if (error.isNotBlank() && (rule.effectiveActualState in setOf("error", "expired") || rule.syncState == "error")) Text(error, color = PortRed',
        'if (error.isNotBlank() && rule.syncState != "stale" && (rule.effectiveActualState in setOf("error", "expired") || rule.syncState == "error")) Text(error, color = PortRed',
        "hide stale runtime errors",
    )
    text = replace_once(
        text,
        '''    "error" -> "同步失败"
    else -> rule.syncState.ifBlank { "同步状态未知" }''',
        '''    "error" -> "同步失败"
    "stale" -> "状态待同步"
    else -> rule.syncState.ifBlank { "同步状态未知" }''',
        "stale sync wording",
    )
    text = replace_once(
        text,
        'rule.syncState == "syncing" -> if (rule.effectiveDesiredState == "stopped") "停止命令已提交 · 正在同步" else "启动命令已提交 · 正在同步"',
        'rule.syncState == "syncing" -> if (rule.effectiveDesiredState == "stopped") "停止命令已提交 · 正在同步" else "启动命令已提交 · 正在同步"\n    rule.syncState == "stale" -> "Agent 在线 · 正在重新获取运行状态"',
        "stale runtime trail",
    )
    return text


def patch_version_and_verifier() -> tuple[str, str]:
    gradle = GRADLE.read_text(encoding="utf-8")
    gradle = replace_once(gradle, "versionCode = 165", "versionCode = 166", "version code")
    gradle = replace_once(gradle, 'versionName = "0.10.23"', 'versionName = "0.10.24"', "version name")

    verifier = VERIFIER.read_text(encoding="utf-8")
    verifier = verifier.replace("versionCode = 165", "versionCode = 166")
    verifier = verifier.replace('versionName = "0.10.23"', 'versionName = "0.10.24"')
    verifier = verifier.replace(
        "build165 NAT, Agent, mapping and home polish verified",
        "build166 home visual consistency and portmap runtime verified",
    )
    verifier = verifier.replace(
        "build165 NAT timing, Agent check and mapping editor verified",
        "build166 home visual consistency and portmap runtime verified",
    )
    return gradle, verifier


def verify(main: str, native: str, portmap: str, gradle: str, verifier: str) -> None:
    combined = "\n".join((main, native, portmap, gradle, verifier))
    required = (
        'versionCode = 166',
        'versionName = "0.10.24"',
        '首页视觉与映射状态修复',
        '"网络健康得分",',
        'softWrap = false',
        'HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", Color(0xFF64748B)',
        'onClick: (() -> Unit)? = null',
        'val techBlue = Color(0xFF2D63D8)',
        'spotColor = techBlue.copy(alpha = .28f)',
        'Agent 在线，正在重新获取映射运行状态',
        'rule.syncState == "stale"',
        'Agent 在线 · 正在重新获取运行状态',
    )
    missing = [item for item in required if item not in combined]
    if missing:
        raise RuntimeError(f"build166 verification failed: {missing}")
    forbidden = (
        'HealthShortcutTile(Icons.Rounded.Terminal, "SSH", "进入", LabV2.Purple',
        'message.replace("刷新成功：", "").ifBlank',
        'modifier = Modifier.weight(1f).clip(RoundedCornerShape(30.dp)).clickable { onNavigate("devices") }',
    )
    found = [item for item in forbidden if item in combined]
    if found:
        raise RuntimeError(f"build166 forbidden state remains: {found}")


def apply() -> None:
    main = patch_main(MAIN.read_text(encoding="utf-8"))
    native = patch_native(NATIVE.read_text(encoding="utf-8"))
    portmap = patch_portmap(PORTMAP.read_text(encoding="utf-8"))
    gradle, verifier = patch_version_and_verifier()

    MAIN.write_text(main, encoding="utf-8")
    NATIVE.write_text(native, encoding="utf-8")
    PORTMAP.write_text(portmap, encoding="utf-8")
    GRADLE.write_text(gradle, encoding="utf-8")
    VERIFIER.write_text(verifier, encoding="utf-8")
    verify(main, native, portmap, gradle, verifier)
    print("Android v0.10.24 build166 home visual and IPv6 mapping state fixes prepared")


if __name__ == "__main__":
    apply()
