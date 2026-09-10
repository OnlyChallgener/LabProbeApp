package com.labprobe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private val WireGuardBlue = LabV2.Primary
private val WireGuardGreen = LabV2.Green
private val WireGuardAmber = LabV2.Amber
private val WireGuardRed = LabV2.Red
private const val WIREGUARD_GATEWAY_TARGET = "wireguard:gateway"
private const val WIREGUARD_SYNC_TARGET = "wireguard:sync"
private fun wireGuardProfileTarget(profileId: String) = "wireguard:profile:$profileId"
private fun wireGuardConnectTarget(profileId: String) = "wireguard:connect:$profileId"
private fun wireGuardSourceColor(source: WireGuardEndpointSource): Color = when (source) {
    WireGuardEndpointSource.MANUAL -> WireGuardGreen
    WireGuardEndpointSource.DDNS -> WireGuardBlue
    WireGuardEndpointSource.STUN -> WireGuardAmber
}

@Composable
fun WireGuardScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(prefs) { WireGuardProfileStore(context, prefs) }
    val controller = remember(prefs) { WireGuardTunnelController.get(context, prefs) }
    val wireGuardHubApi = remember(prefs.hub, prefs.token, prefs.hubDns) { WireGuardHubApi(prefs) }
    val routerRepository = remember(prefs.hub, prefs.token, prefs.hubDns) { RouterRepositoryRegistry.get(prefs) }
    val operations = remember(prefs.hub, prefs.token, prefs.hubDns) { NetworkOperationRegistry.get(prefs) }
    val operation by operations.state.collectAsState()
    val labProbeDdns by routerRepository.labProbeDdns.collectAsState()
    val nativeDdns by routerRepository.ddns.collectAsState()
    var profiles by remember { mutableStateOf<List<WireGuardProfile>>(emptyList()) }
    var runtime by remember { mutableStateOf(WireGuardRuntimeStatus()) }
    var editor by remember { mutableStateOf<WireGuardProfile?>(null) }
    var editingExisting by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<WireGuardProfile?>(null) }
    var requestedStart by remember { mutableStateOf<WireGuardProfile?>(null) }
    var pendingServerEnable by remember { mutableStateOf<WireGuardProfile?>(null) }
    var startCheckInProgress by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var stunRules by remember { mutableStateOf<List<StunRule>>(emptyList()) }
    var serverConfig by remember { mutableStateOf(WireGuardServerConfig()) }
    var showServerSettings by remember { mutableStateOf(false) }
    var editorClientPublicKey by remember { mutableStateOf("") }

    suspend fun reload() {
        profiles = withContext(Dispatchers.IO) { store.load() }
    }

    LaunchedEffect(store) {
        reload()
        runCatching { wireGuardHubApi.loadServerConfig() }.onSuccess { serverConfig = it }
    }

    LaunchedEffect(operation?.completedVersion) {
        if ((operation?.completedVersion ?: 0L) > 0L) reload()
    }

    suspend fun provisionManaged(original: WireGuardProfile, announce: Boolean = true): WireGuardProfile {
        var profile = original
        if (profile.endpointSource == WireGuardEndpointSource.STUN) {
            val binding = wireGuardHubApi.ensureStunBinding(profile)
            if (binding != profile.endpointBindingId) {
                profile = withContext(Dispatchers.IO) {
                    store.applyProvisioningResult(profile.id, profile.serverPublicKey, binding)
                    store.load().first { it.id == profile.id }
                }
            }
        }
        val clientPublicKey = withContext(Dispatchers.IO) { wireGuardPublicKey(store.privateKey(profile.id)) }
        val result = wireGuardHubApi.provision(profile, clientPublicKey)
        profile = withContext(Dispatchers.IO) {
            store.applyProvisionedProfile(profile.id, result.profile)
        } ?: result.profile
        if (profile.endpoint.isNotBlank()) wireGuardHubApi.patchManagedEndpoint(profile)
        reload()
        if (announce) message = "${profile.name} 已同步到 Agent，可开始连接"
        return profile
    }

    suspend fun applyManagedEndpoint(profile: WireGuardProfile, previousEndpoint: String) {
        if (operations.state.value?.running == true) return
        if (profile.endpoint == previousEndpoint || profile.endpoint.isBlank() || profile.endpointUpdateError.isNotBlank()) return
        try {
            wireGuardHubApi.patchManagedEndpoint(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            withContext(Dispatchers.IO) {
                store.markEndpointError(profile.id, profile.endpointSource, "地址已保存在手机，Hub 同步失败：${uiMessageZh(error.message)}")
            }
            return
        }
        if (operations.state.value?.running == true) return
        if (runtime.running && runtime.profileId == profile.id) {
            val privateKey = withContext(Dispatchers.IO) { store.privateKey(profile.id) }
            when (val result = controller.start(profile, privateKey)) {
                WireGuardStartResult.Started -> message = "${profile.endpointSource.displayName} 地址已更新，连接已自动重载"
                is WireGuardStartResult.Failed -> message = result.message
                is WireGuardStartResult.PermissionRequired -> Unit
            }
        }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val profile = pendingStart
        pendingStart = null
        if (profile != null) {
            if (result.resultCode == android.app.Activity.RESULT_OK) requestedStart = profile
            else message = "系统尚未授予 VPN权限"
        }
    }

    suspend fun startClient(profile: WireGuardProfile): Boolean {
        val privateKey = withContext(Dispatchers.IO) { store.privateKey(profile.id) }
        return when (val result = controller.start(profile, privateKey)) {
            WireGuardStartResult.Started -> {
                message = "${profile.name} 已启动"
                runtime = controller.status()
                true
            }
            is WireGuardStartResult.PermissionRequired -> {
                pendingStart = profile.also { vpnPermission.launch(result.intent) }
                false
            }
            is WireGuardStartResult.Failed -> {
                message = result.message
                throw IllegalStateException(result.message)
            }
        }
    }

    fun requestStart(profile: WireGuardProfile) {
        if (!startCheckInProgress && operation?.running != true) requestedStart = profile
    }

    LaunchedEffect(requestedStart) {
        val profile = requestedStart ?: return@LaunchedEffect
        requestedStart = null
        val launched = operations.launch(wireGuardConnectTarget(profile.id), "正在准备 ${profile.name}…") { report ->
            startCheckInProgress = true
            try {
                if (profile.endpointSource == WireGuardEndpointSource.MANUAL) {
                    report("正在启动 ${profile.name}…")
                    val started = startClient(profile)
                    report(if (started) "${profile.name} 已启动" else "等待系统 VPN 授权")
                    return@launch
                }
                if (profile.endpointSource == WireGuardEndpointSource.STUN) {
                    require(profile.endpointBindingId.isNotBlank() && profile.endpointUpdateError.isBlank()) {
                        profile.endpointUpdateError.ifBlank { "STUN 绑定尚未就绪，请先保存并同步配置" }
                    }
                }
                report("正在检查 WireGuard 网关状态…")
                val state = wireGuardHubApi.loadServerState()
                serverConfig = state.config
                when {
                    !state.config.enabled -> {
                        pendingServerEnable = profile
                        report("等待确认启用 WireGuard 网关")
                    }
                    !isWireGuardServerReady(state) -> {
                        val error = wireGuardServerErrorForRevision(state, state.config.revision)
                        throw IllegalStateException(error.ifBlank { "WireGuard 网关尚未就绪" })
                    }
                    else -> {
                        report("正在启动 ${profile.name}…")
                        val started = startClient(profile)
                        report(if (started) "${profile.name} 已启动" else "等待系统 VPN 授权")
                    }
                }
            } finally {
                startCheckInProgress = false
            }
        }
        if (!launched) message = "已有网络配置操作正在进行，请稍候"
    }

    LaunchedEffect(Unit) {
        var serverPollTick = 0
        while (true) {
            runtime = controller.status()
            if (serverPollTick % 5 == 0 && operations.state.value?.running != true) {
                val serverState = runCatching { wireGuardHubApi.loadServerState() }.getOrNull()
                if (serverState != null) {
                    serverConfig = serverState.config
                    val runningProfile = profiles.firstOrNull { it.id == runtime.profileId }
                    if (!serverState.config.enabled && runtime.running && runningProfile?.endpointSource != WireGuardEndpointSource.MANUAL) {
                        runtime = controller.stop()
                    }
                }
            }
            serverPollTick++
            delay(2_000L)
        }
    }
    LaunchedEffect(routerRepository) {
        routerRepository.refreshLabProbeDdns(false)
        routerRepository.refreshDdns(false)
    }
    LaunchedEffect(labProbeDdns.updatedAt, nativeDdns.updatedAt, profiles.map { it.id to it.endpointRevision }, operation?.running) {
        if (operations.state.value?.running == true) return@LaunchedEffect
        val operationVersion = operations.state.value?.completedVersion ?: 0L
        val current = withContext(Dispatchers.IO) { store.load() }
        if (operations.state.value?.running == true || operations.state.value?.completedVersion != operationVersion) return@LaunchedEffect
        if (current.any { it.endpointSource == WireGuardEndpointSource.DDNS }) {
            val updated = withContext(Dispatchers.IO) {
                if (operations.state.value?.running == true || operations.state.value?.completedVersion != operationVersion) {
                    return@withContext null
                }
                WireGuardEndpointCoordinator.applyDdnsSnapshot(
                    store,
                    current,
                    labProbeDdns.value?.records.orEmpty(),
                    nativeDdns.value.orEmpty(),
                )
                store.load()
            } ?: return@LaunchedEffect
            updated.filter { it.endpointSource == WireGuardEndpointSource.DDNS }.forEach { profile ->
                applyManagedEndpoint(profile, current.firstOrNull { it.id == profile.id }?.endpoint.orEmpty())
            }
            reload()
        }
    }
    LaunchedEffect(profiles.map { Triple(it.id, it.endpointBindingId, it.endpointRevision) }, operation?.completedVersion) {
        val stunApi = StunApi(prefs)
        while (true) {
            if (operations.state.value?.running == true) {
                delay(1_000L)
                continue
            }
            val operationVersion = operations.state.value?.completedVersion ?: 0L
            val current = withContext(Dispatchers.IO) { store.load() }
            if (current.any { it.endpointSource == WireGuardEndpointSource.STUN }) {
                runCatching {
                    val currentServerConfig = wireGuardHubApi.loadServerConfig()
                    Triple(stunApi.list(), currentServerConfig, wireGuardHubApi.loadRouterLanIp())
                }.onSuccess { (snapshot, currentServerConfig, routerIp) ->
                    if (!snapshot.rulesLoaded) return@onSuccess
                    if (operations.state.value?.running == true || operations.state.value?.completedVersion != operationVersion) return@onSuccess
                    serverConfig = currentServerConfig
                    stunRules = snapshot.rules.filter { it.transportProtocol == "UDP" && it.targetPort == currentServerConfig.listenPort }
                    val updated = withContext(Dispatchers.IO) {
                        if (operations.state.value?.running == true || operations.state.value?.completedVersion != operationVersion) {
                            return@withContext null
                        }
                        WireGuardEndpointCoordinator.applyStunSnapshot(
                            store,
                            current,
                            snapshot.rules,
                            currentServerConfig.listenPort,
                            routerIp,
                        )
                        store.load()
                    } ?: return@onSuccess
                    updated.filter { it.endpointSource == WireGuardEndpointSource.STUN }.forEach { profile ->
                        applyManagedEndpoint(profile, current.firstOrNull { it.id == profile.id }?.endpoint.orEmpty())
                    }
                    reload()
                }.onFailure {
                    withContext(Dispatchers.IO) {
                        current.filter { it.endpointSource == WireGuardEndpointSource.STUN }
                            .forEach { profile -> store.markEndpointError(profile.id, WireGuardEndpointSource.STUN, "STUN 状态暂时不可用，保留上次地址") }
                    }
                }
            } else {
                runCatching { stunApi.list() }.onSuccess { snapshot ->
                    if (!snapshot.rulesLoaded) return@onSuccess
                    stunRules = snapshot.rules.filter { it.transportProtocol == "UDP" && it.targetPort == serverConfig.listenPort }
                }
            }
            delay(15_000L)
        }
    }

    DetailShell(
        title = "WireGuard",
        subtitle = "官方隧道内核 · DDNS 与 STUN 使用独立配置",
        onBack = onBack,
        compactHeader = true,
        unifiedTypography = true,
    ) {
        val runningProfile = profiles.firstOrNull { it.id == runtime.profileId }
        val gatewayRelevant = runningProfile?.endpointSource != WireGuardEndpointSource.MANUAL
        val isHandshaked = (!gatewayRelevant || serverConfig.enabled) && runtime.running && runtime.latestHandshakeAt > 0L && (System.currentTimeMillis() - runtime.latestHandshakeAt) < 180_000L
        LabCoreCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabV2ToolIcon(Icons.Rounded.Shield, WireGuardBlue, size = 38)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("客户端连接", style = LabTypography.CardTitle)
                    Text(
                        when {
                            !runtime.running -> "未连接 · 同一时间只启用一个配置"
                            isHandshaked -> "已握手 (${formatHandshakeTime(runtime.latestHandshakeAt)}) · ${formatWireGuardBytes(runtime.receivedBytes)} 下行 / ${formatWireGuardBytes(runtime.sentBytes)} 上行"
                            else -> "正在尝试握手… (未收到服务端回包，请排查密钥/端口/网络) · 发送 ${formatWireGuardBytes(runtime.sentBytes)}"
                        },
                        style = LabTypography.Caption.copy(color = when {
                            !runtime.running -> LabV2.InkMuted
                            isHandshaked -> WireGuardGreen
                            else -> WireGuardAmber
                        }),
                        maxLines = 2,
                    )
                }
                if (runtime.running) {
                    val badgeColor = if (isHandshaked) WireGuardGreen else WireGuardAmber
                    Surface(color = badgeColor.copy(alpha = .12f), shape = androidx.compose.foundation.shape.RoundedCornerShape(99.dp)) {
                        Text(if (isHandshaked) "已握手" else "握手中", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                "MVP 仅路由家庭内网网段；不会接管全部手机流量。切换配置时官方 WireGuard 后端会短暂重连。",
                style = LabTypography.Caption.copy(color = LabV2.InkMuted),
            )
            Surface(
                color = LabCoreSurface.Inner,
                shape = LabCoreSurface.InnerShape,
                border = BorderStroke(1.dp, LabCoreSurface.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(16.dp), tint = if (serverConfig.enabled) LabV2.InkMuted else WireGuardAmber)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "网关: ${if (serverConfig.enabled) "已启用" else "已停用"} · 端口 ${serverConfig.listenPort} · MTU ${serverConfig.mtu} · ${serverConfig.address}",
                        style = LabTypography.Caption.copy(color = if (serverConfig.enabled) LabV2.InkMuted else WireGuardAmber),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = { showServerSettings = true },
                        enabled = operation?.running != true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("修改", style = LabTypography.CompactButton.copy(color = LabV2.Primary))
                    }
                }
            }
        }


        val duplicateServerKeyIds = profiles.filter { it.serverPublicKey.isNotBlank() }
            .groupBy { it.serverPublicKey }
            .filterValues { it.size > 1 }
            .values.flatten().map { it.id }.toSet()
        listOf(
            WireGuardEndpointSource.MANUAL to "我的配置",
            WireGuardEndpointSource.DDNS to "DDNS 自动",
            WireGuardEndpointSource.STUN to "STUN 自动",
        ).forEach { (source, title) ->
            val group = profiles.filter { it.endpointSource == source }
            if (group.isNotEmpty()) {
                Text(title, style = LabTypography.SectionTitle, color = LabV2.Ink)
                group.forEach { profile ->
                    val isActive = runtime.running && runtime.profileId == profile.id &&
                        (profile.endpointSource == WireGuardEndpointSource.MANUAL || serverConfig.enabled)
                    WireGuardProfileCard(
                        profile = profile,
                        active = isActive,
                        runtime = if (isActive) runtime else null,
                        operation = operation?.takeIf { it.targetId.endsWith(":${profile.id}") },
                        actionsEnabled = operation?.running != true,
                        onStart = { requestStart(profile) },
                        onStop = {
                            val launched = operations.launch(wireGuardConnectTarget(profile.id), "正在停止 ${profile.name}…") { report ->
                                runtime = controller.stop()
                                message = "WireGuard 已停止"
                                report("${profile.name} 已停止")
                            }
                            if (!launched) message = "已有网络配置操作正在进行，请稍候"
                        },
                        onEdit = { editor = profile; editingExisting = true },
                    )
                }
            }
        }

        if (profiles.none { it.endpointSource == WireGuardEndpointSource.MANUAL }) {
            WireGuardCreateCard(WireGuardEndpointSource.MANUAL, operation?.running != true) {
                editor = WireGuardProfile.newProfile(WireGuardEndpointSource.MANUAL)
                editingExisting = false
            }
        }
        if (profiles.none { it.endpointSource == WireGuardEndpointSource.DDNS }) {
            WireGuardCreateCard(WireGuardEndpointSource.DDNS, operation?.running != true) {
                editor = WireGuardProfile.newProfile(WireGuardEndpointSource.DDNS).copy(
                    endpointPort = serverConfig.listenPort,
                    followsServerPort = true,
                )
                editingExisting = false
            }
        }
        if (profiles.none { it.endpointSource == WireGuardEndpointSource.STUN }) {
            WireGuardCreateCard(WireGuardEndpointSource.STUN, operation?.running != true) {
                editor = WireGuardProfile.newProfile(WireGuardEndpointSource.STUN)
                editingExisting = false
            }
        }

        OutlinedButton(
            onClick = {
                val launched = operations.launch(WIREGUARD_SYNC_TARGET, "正在读取自动配置…") { report ->
                    val managed = withContext(Dispatchers.IO) {
                        store.load().filter { it.endpointSource != WireGuardEndpointSource.MANUAL }
                    }
                    managed.forEachIndexed { index, profile ->
                        report("正在同步 ${profile.name}（${index + 1}/${managed.size}）…")
                        provisionManaged(profile, announce = false)
                    }
                    report(if (managed.isEmpty()) "没有需要同步的自动配置" else "${managed.size} 个自动配置已同步到 Agent")
                }
                if (!launched) message = "已有网络配置操作正在进行，请稍候"
            },
            enabled = operation?.running != true,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, WireGuardBlue.copy(alpha = .32f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WireGuardBlue),
            shape = LabCoreSurface.InnerShape,
        ) {
            Icon(Icons.Rounded.CloudSync, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (operation?.targetId == WIREGUARD_SYNC_TARGET && operation?.running == true) "正在同步 Agent…" else "重新同步自动配置", style = LabTypography.CompactButton)
        }
        operation?.let { WireGuardOperationStatus(it) }
        if (message.isNotBlank()) Text(message, style = LabTypography.Caption.copy(color = if (message.contains("失败") || message.contains("不可用")) WireGuardRed else LabV2.InkMuted))
    }

    if (showServerSettings) {
        WireGuardServerSettingsDialog(
            initial = serverConfig,
            operation = operation?.takeIf { it.targetId == WIREGUARD_GATEWAY_TARGET },
            onDismiss = { showServerSettings = false },
            onSave = { listenPort, mtu, address, enabled ->
                operations.launch(WIREGUARD_GATEWAY_TARGET, "正在准备网关修改…") { report ->
                    val freshProfiles = withContext(Dispatchers.IO) { store.load() }
                    val result = wireGuardHubApi.updateServerConfigAndSync(
                        listenPort = listenPort,
                        mtu = mtu,
                        address = address,
                        enabled = enabled,
                        profiles = freshProfiles,
                        resolveProfilesForPort = { freshPort ->
                            store.rememberServerPortAuthority(freshPort)
                            store.load()
                        },
                        onProgress = report,
                    )
                    if (result.pendingStunRuleIds.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            freshProfiles.filter {
                                it.endpointSource == WireGuardEndpointSource.STUN &&
                                    it.endpointBindingId in result.pendingStunRuleIds
                            }.forEach {
                                store.markEndpointError(
                                    it.id,
                                    WireGuardEndpointSource.STUN,
                                    "网关端口已修改，绑定的 STUN 规则仍待同步；旧地址不可用于连接",
                                )
                            }
                        }
                        reload()
                    }
                    if (!result.applied) {
                        val details = (result.warnings + result.pendingStunRuleIds.map { "STUN $it 待同步" })
                            .distinct().joinToString("；")
                        report("网关修改已提交，Agent 尚未确认应用")
                        throw IllegalStateException("网关修改已提交但尚未确认应用${details.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
                    }
                    val previousRunningProfile = freshProfiles.firstOrNull { it.id == runtime.profileId }
                    val updatedRunningProfile = withContext(Dispatchers.IO) {
                        store.applyServerConfig(result.config.listenPort, result.config.mtu, result.previousListenPort)
                        store.load().firstOrNull { it.id == previousRunningProfile?.id }
                    }
                    serverConfig = result.config
                    val hadRunningClient = runtime.running
                    val managedClientRunning = hadRunningClient && previousRunningProfile?.endpointSource != WireGuardEndpointSource.MANUAL
                    if (!result.config.enabled && managedClientRunning) {
                        runtime = controller.stop()
                    } else if (result.config.enabled && managedClientRunning &&
                        updatedRunningProfile?.endpointSource == WireGuardEndpointSource.STUN &&
                        updatedRunningProfile.endpointUpdateError.isNotBlank()) {
                        runtime = controller.stop()
                        report("网关已应用；当前 STUN 规则待同步，客户端已停止以避免使用旧地址")
                    } else if (result.config.enabled && managedClientRunning && previousRunningProfile != null && updatedRunningProfile != null &&
                        (previousRunningProfile.endpoint != updatedRunningProfile.endpoint || previousRunningProfile.mtu != updatedRunningProfile.mtu)) {
                        updatedRunningProfile.let { updatedProfile ->
                            report("网关已应用，正在重载当前客户端配置…")
                            val privateKey = withContext(Dispatchers.IO) { store.privateKey(updatedProfile.id) }
                            when (val restart = controller.start(updatedProfile, privateKey)) {
                                WireGuardStartResult.Started -> runtime = controller.status()
                                is WireGuardStartResult.Failed -> throw IllegalStateException(restart.message)
                                is WireGuardStartResult.PermissionRequired -> throw IllegalStateException("网关已应用，但客户端重载需要重新授权 VPN")
                            }
                        }
                    }
                    reload()
                    val notices = (result.warnings + result.pendingStunRuleIds.map { "STUN $it 待同步" }).distinct()
                    report(
                        when {
                            !result.config.enabled && managedClientRunning -> "WireGuard 网关已停用，托管客户端连接已停止"
                            !result.config.enabled -> "WireGuard 网关配置已应用并停用"
                            notices.isNotEmpty() -> "网关配置已应用；${notices.joinToString("；")}"
                            else -> "网关配置已由 Agent 确认应用（端口 ${result.config.listenPort} · MTU ${result.config.mtu}）"
                        }
                    )
                }
            }
        )
    }

    pendingServerEnable?.let { profile ->
        WireGuardEnableServerDialog(
            onCancel = {
                pendingServerEnable = null
                message = ""
            },
            onConfirm = {
                pendingServerEnable = null
                val launched = operations.launch(wireGuardConnectTarget(profile.id), "正在启用 WireGuard 网关…") { report ->
                    startCheckInProgress = true
                    try {
                        if (profile.endpointSource == WireGuardEndpointSource.STUN) {
                            require(profile.endpointBindingId.isNotBlank() && profile.endpointUpdateError.isBlank()) {
                                profile.endpointUpdateError.ifBlank { "STUN 绑定尚未就绪，请先保存并同步配置" }
                            }
                        }
                        val state = wireGuardHubApi.enableServerAndAwaitReady()
                        serverConfig = state.config
                        report("网关已启用，正在启动 ${profile.name}…")
                        val started = startClient(profile)
                        report(if (started) "网关已启用，${profile.name} 已启动" else "网关已启用，等待系统 VPN 授权")
                    } finally {
                        startCheckInProgress = false
                    }
                }
                if (!launched) {
                    message = "已有网络配置操作正在进行，请稍候"
                    pendingServerEnable = profile
                }
            },
        )
    }

    LaunchedEffect(editor?.id) {
        val profile = editor
        editorClientPublicKey = if (profile == null) "" else withContext(Dispatchers.IO) {
            wireGuardPublicKey(store.privateKey(profile.id))
        }
    }

    editor?.let { profile ->
        val clientPub = editorClientPublicKey
        val isDuplicateKey = profile.serverPublicKey.isNotBlank() && profiles.count { it.serverPublicKey == profile.serverPublicKey && it.id != profile.id } > 0
        WireGuardEditorDialog(
            initial = profile,
            isExisting = editingExisting,
            clientPublicKey = clientPub,
            duplicateServerKey = isDuplicateKey,
            availableStunRules = stunRules,
            serverListenPort = serverConfig.listenPort,
            operation = operation,
            onDismiss = { editor = null },
            onDelete = {
                operations.launch(wireGuardProfileTarget(profile.id), "正在删除 ${profile.name}…") { report ->
                    if (runtime.profileId == profile.id) {
                        report("正在停止 ${profile.name}…")
                        runtime = controller.stop()
                    }
                    if (profile.endpointSource != WireGuardEndpointSource.MANUAL) {
                        report("正在从 Agent 移除 ${profile.name}…")
                        wireGuardHubApi.removeAutomaticProfile(profile, clientPub)
                    }
                    withContext(Dispatchers.IO) { store.delete(profile.id) }
                    reload()
                    report(
                        if (profile.endpointSource == WireGuardEndpointSource.MANUAL) "已删除手动配置 ${profile.name}"
                        else "已删除 ${profile.name}，Agent 端 Peer 已移除"
                    )
                }
            },
            onCopyClientKey = { copyWireGuard(context, "WireGuard 客户端公钥", clientPub) },
            onSave = { edited ->
                val existingAtLaunch = editingExisting
                operations.launch(wireGuardProfileTarget(edited.id), "正在保存 ${edited.name}…") { report ->
                    withContext(Dispatchers.IO) {
                        if (existingAtLaunch) store.saveProfileEdit(edited) else store.create(edited)
                    }
                    reload()
                    if (edited.endpointSource == WireGuardEndpointSource.MANUAL) {
                        report(if (existingAtLaunch) "手动配置已保存" else "已创建手动配置，并生成客户端密钥")
                    } else {
                        report("配置已保存，正在同步 Agent…")
                        val saved = withContext(Dispatchers.IO) { store.load().first { it.id == edited.id } }
                        try {
                            val provisioned = provisionManaged(saved, announce = false)
                            report("${provisioned.name} 已由 Agent 确认应用")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            withContext(Dispatchers.IO) {
                                store.markEndpointError(saved.id, saved.endpointSource, uiMessageZh(error.message).ifBlank { "Agent 同步失败" })
                            }
                            reload()
                            throw IllegalStateException("配置已保存在本机，但 Agent 同步失败：${uiMessageZh(error.message)}", error)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun WireGuardCreateCard(source: WireGuardEndpointSource, enabled: Boolean, onCreate: () -> Unit) {
    val color = wireGuardSourceColor(source)
    OutlinedButton(
        onClick = onCreate,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, color.copy(alpha = .36f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        shape = LabCoreSurface.InnerShape,
    ) {
        Icon(Icons.Rounded.Add, null, Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("添加 ${source.displayName} 配置", style = LabTypography.CompactButton)
    }
}

@Composable
private fun WireGuardProfileCard(
    profile: WireGuardProfile,
    active: Boolean,
    runtime: WireGuardRuntimeStatus? = null,
    operation: NetworkOperationState? = null,
    actionsEnabled: Boolean = true,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = wireGuardSourceColor(profile.endpointSource)
    val isHandshaked = active && runtime != null && runtime.latestHandshakeAt > 0L && (System.currentTimeMillis() - runtime.latestHandshakeAt) < 180_000L
    LabCoreCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabV2ToolIcon(if (profile.endpointSource == WireGuardEndpointSource.STUN) Icons.Rounded.Key else Icons.Rounded.Public, accent, size = 34)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.name, style = LabTypography.CardTitle.copy(fontSize = 14.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.endpointSource.displayName, style = LabTypography.Caption.copy(color = accent), fontWeight = FontWeight.SemiBold)
            }
            if (active) Icon(Icons.Rounded.CheckCircle, "已启用", tint = if (isHandshaked) WireGuardGreen else WireGuardAmber, modifier = Modifier.size(20.dp))
        }
        if (active && runtime != null) {
            Surface(
                color = if (isHandshaked) WireGuardGreen.copy(alpha = .08f) else WireGuardAmber.copy(alpha = .08f),
                shape = LabCoreSurface.InnerShape,
                border = BorderStroke(1.dp, if (isHandshaked) WireGuardGreen.copy(alpha = .24f) else WireGuardAmber.copy(alpha = .24f)),
            ) {

                Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isHandshaked) "已握手 (${formatHandshakeTime(runtime.latestHandshakeAt)})" else "正在握手 (等待服务端响应…)",
                        Modifier.weight(1f),
                        style = LabTypography.Caption.copy(color = if (isHandshaked) WireGuardGreen else WireGuardAmber),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${formatWireGuardBytes(runtime.receivedBytes)} ↓ / ${formatWireGuardBytes(runtime.sentBytes)} ↑",
                        style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                    )
                }
            }
        }
        Surface(color = LabCoreSurface.Inner, shape = LabCoreSurface.InnerShape, border = BorderStroke(1.dp, LabCoreSurface.Border)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (profile.endpoint.isBlank()) "等待 ${profile.endpointSource.displayName} 地址" else profile.endpoint,
                    style = LabTypography.Value.copy(
                        color = if (profile.endpoint.isBlank()) LabV2.InkMuted else LabV2.Ink,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when (profile.endpointSource) {
                        WireGuardEndpointSource.MANUAL -> "手动维护地址；不参与自动更新"
                        WireGuardEndpointSource.DDNS -> "域名保持不变，DNS 自动解析最新 A 记录"
                        WireGuardEndpointSource.STUN -> "仅由 STUN 更新器写入动态公网 IP 与端口"
                    },
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        profile.endpointUpdateError.takeIf { it.isNotBlank() }?.let { Text(it, style = LabTypography.Caption.copy(color = WireGuardAmber), maxLines = 2) }
        operation?.let { WireGuardOperationStatus(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onEdit,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, LabCoreSurface.Border),
                shape = LabCoreSurface.InnerShape
            ) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("编辑", style = LabTypography.CompactButton)
            }
            if (active) {
                Button(
                    onClick = onStop,
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = WireGuardRed, contentColor = Color.White),
                    shape = LabCoreSurface.InnerShape
                ) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("停止连接", style = LabTypography.CompactButton)
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    enabled = actionsEnabled && profile.isComplete &&
                        (profile.endpointSource != WireGuardEndpointSource.STUN || profile.endpointUpdateError.isBlank()),
                    shape = LabCoreSurface.InnerShape
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("启动连接", style = LabTypography.CompactButton)
                }
            }
        }
    }
}

@Composable
private fun WireGuardOperationStatus(operation: NetworkOperationState) {
    val color = when {
        operation.error != null -> WireGuardRed
        operation.running -> WireGuardBlue
        else -> WireGuardGreen
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = .07f),
        shape = LabCoreSurface.InnerShape,
        border = BorderStroke(1.dp, color.copy(alpha = .20f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (operation.running) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
                Spacer(Modifier.width(7.dp))
            }
            Text(
                operation.error?.let { uiMessageZh(it) } ?: operation.label,
                modifier = Modifier.weight(1f),
                style = LabTypography.Caption.copy(color = color),
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun WireGuardEnableServerDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = LabCoreSurface.CardShape, color = Color.White, shadowElevation = 10.dp) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("WireGuard 网关已停用", style = LabTypography.CardTitle)
                Text(
                    "当前配置需要先启用路由器上的 WireGuard 服务端。",
                    style = LabTypography.Body.copy(color = LabV2.InkMuted),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = LabCoreSurface.InnerShape,
                    ) {
                        Text("取消", style = LabTypography.Button)
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(containerColor = WireGuardBlue),
                        shape = LabCoreSurface.InnerShape,
                    ) {
                        Text("启用并连接", style = LabTypography.Button)
                    }
                }
            }
        }
    }
}

@Composable
private fun WireGuardEditorDialog(
    initial: WireGuardProfile,
    isExisting: Boolean,
    clientPublicKey: String,
    duplicateServerKey: Boolean,
    availableStunRules: List<StunRule>,
    serverListenPort: Int,
    operation: NetworkOperationState?,
    onDismiss: () -> Unit,
    onDelete: () -> Boolean,
    onCopyClientKey: () -> Unit,
    onSave: (WireGuardProfile) -> Boolean,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var host by remember(initial.id) { mutableStateOf(initial.endpointHost) }
    var port by remember(initial.id) { mutableStateOf(initial.endpointPort.toString()) }
    var address by remember(initial.id) { mutableStateOf(initial.interfaceAddresses.joinToString(", ")) }
    var dns by remember(initial.id) { mutableStateOf(initial.dnsServers.joinToString(", ")) }
    var serverKey by remember(initial.id) { mutableStateOf(initial.serverPublicKey) }
    var allowedIps by remember(initial.id) { mutableStateOf(initial.allowedIps.joinToString(", ")) }
    var bindingId by remember(initial.id) { mutableStateOf(initial.endpointBindingId) }
    var error by remember { mutableStateOf("") }
    var source by remember(initial.id) { mutableStateOf(initial.endpointSource) }
    var submittedAfterVersion by remember(initial.id) { mutableStateOf<Long?>(null) }
    val operationTarget = wireGuardProfileTarget(initial.id)
    val busy = operation?.running == true
    val followsGatewayPort = source == WireGuardEndpointSource.DDNS && when {
        initial.endpointSource != WireGuardEndpointSource.DDNS -> true
        initial.followsServerPort != null -> initial.followsServerPort
        else -> initial.endpointPort == serverListenPort
    }
    val automaticEndpointChanged = isExisting && initial.endpointSource != WireGuardEndpointSource.MANUAL && source == initial.endpointSource &&
        host.trim() != initial.endpointHost

    LaunchedEffect(operation?.completedVersion) {
        val floor = submittedAfterVersion ?: return@LaunchedEffect
        if (operation?.targetId != operationTarget || operation.running || operation.completedVersion <= floor) return@LaunchedEffect
        submittedAfterVersion = null
        if (operation.error == null) onDismiss() else error = uiMessageZh(operation.error)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.93f).heightIn(max = 720.dp),
            shape = LabV2.CardShape,
            color = Color.White,
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(if (isExisting) "编辑 WireGuard 配置" else "新建 WireGuard 配置", style = LabTypography.PageTitle)
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(99.dp), color = wireGuardSourceColor(source).copy(alpha = .10f)) {
                    Text(source.displayName, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = LabTypography.Caption.copy(color = wireGuardSourceColor(source)), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (source == WireGuardEndpointSource.MANUAL) "手动地址锁定，不参与 DDNS/STUN 自动更新。" else "自动配置只能由自己的地址来源更新；手动改地址需明确转为手动。",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted)
                )
                if (duplicateServerKey) {
                    Text("提示：与其他配置使用相同服务端公钥；仅提示，不会合并或覆盖配置", style = LabTypography.Caption.copy(color = WireGuardAmber))
                }
                WireGuardField(name, { name = it }, "名称")
                if (source != WireGuardEndpointSource.STUN) {
                    WireGuardField(host, { host = it }, if (source == WireGuardEndpointSource.MANUAL) "服务器地址" else "DDNS 域名")
                    if (source == WireGuardEndpointSource.DDNS) {
                        WireGuardField(
                            if (followsGatewayPort) serverListenPort.toString() else port,
                            {},
                            if (followsGatewayPort) "UDP 端口（跟随网关）" else "UDP 端口（固定保留）",
                            KeyboardType.Number,
                            readOnly = true,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (followsGatewayPort) "此配置自动跟随网关监听端口。" else "这是原有自定义端口；不会随网关修改。",
                                Modifier.weight(1f),
                                style = LabTypography.Caption.copy(color = if (followsGatewayPort) LabV2.InkMuted else WireGuardAmber),
                            )
                            if (!followsGatewayPort) {
                                TextButton(onClick = { source = WireGuardEndpointSource.MANUAL }, enabled = !busy) {
                                    Text("转为手动编辑", style = LabTypography.CompactButton.copy(color = WireGuardAmber))
                                }
                            }
                        }
                    } else {
                        WireGuardField(port, { port = it.filter(Char::isDigit) }, "UDP 端口", KeyboardType.Number)
                    }
                } else {
                    Surface(shape = LabCoreSurface.InnerShape, color = WireGuardAmber.copy(alpha = .07f), border = BorderStroke(1.dp, WireGuardAmber.copy(alpha = .18f))) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("STUN UDP 穿透", style = LabTypography.SectionTitle.copy(color = LabV2.Ink))
                            if (availableStunRules.isEmpty()) {
                                Text("保存时自动创建到路由器 WireGuard 端口的穿透规则。", style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                            } else {
                                Text("选择一个现有规则；不选择时自动使用 WireGuard 规则。", style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                                availableStunRules.take(4).forEach { rule ->
                                    FilterChip(
                                        selected = bindingId == rule.id,
                                        onClick = { bindingId = if (bindingId == rule.id) "" else rule.id },
                                        enabled = !busy,
                                        label = { Text("${rule.name} · ${rule.targetIpv4}:${rule.targetPort}", style = LabTypography.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = WireGuardAmber.copy(alpha = .12f), selectedLabelColor = LabV2.Ink),
                                    )
                                }
                            }
                        }
                    }
                }
                if (automaticEndpointChanged) {
                    Surface(shape = LabCoreSurface.InnerShape, color = WireGuardAmber.copy(alpha = .10f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("自动地址不可直接改写", Modifier.weight(1f), style = LabTypography.Caption.copy(color = WireGuardAmber), fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { source = WireGuardEndpointSource.MANUAL }) { Text("转为手动", style = LabTypography.CompactButton.copy(color = WireGuardAmber)) }
                        }
                    }
                }
                WireGuardField(address, { address = it }, "客户端隧道地址，例如 10.77.0.2/32")
                if (source == WireGuardEndpointSource.MANUAL) {
                    WireGuardField(serverKey, { serverKey = it.trim() }, "服务端公钥")
                } else {
                    Surface(shape = LabCoreSurface.InnerShape, color = WireGuardBlue.copy(alpha = .06f)) {
                        Text(
                            if (serverKey.isBlank()) "服务端公钥将在保存后由 Agent 安全返回。" else "Agent 服务端公钥已同步，无需手动维护。",
                            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                            style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                        )
                    }
                }
                if (clientPublicKey.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("客户端公钥", style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                            Text(clientPublicKey.take(16) + "...", style = LabTypography.Caption.copy(fontFamily = FontFamily.Monospace, color = LabV2.Ink))
                        }
                        TextButton(onClick = onCopyClientKey, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制公钥", style = LabTypography.CompactButton)
                        }
                    }
                }
                val isFullTunnel = allowedIps.contains("0.0.0.0/0")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("路由网段与分流模式", style = LabTypography.Caption.copy(color = LabV2.InkMuted), fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isFullTunnel,
                            onClick = {
                                if (isFullTunnel) allowedIps = "10.77.0.0/24, 192.168.5.0/24"
                            },
                            label = { Text("内网分流 (推荐)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = WireGuardBlue.copy(alpha = .12f), selectedLabelColor = LabV2.Ink),
                        )
                        FilterChip(
                            selected = isFullTunnel,
                            onClick = {
                                allowedIps = "0.0.0.0/0, ::/0"
                            },
                            label = { Text("全局代理 (0.0.0.0/0)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = WireGuardAmber.copy(alpha = .14f), selectedLabelColor = LabV2.Ink),
                        )
                    }

                }
                WireGuardField(allowedIps, { allowedIps = it }, if (isFullTunnel) "路由网段（全局接管：0.0.0.0/0, ::/0）" else "路由网段，例如 10.77.0.0/24, 192.168.5.0/24")
                WireGuardField(dns, { dns = it }, "隧道 DNS（可选）")
                operation?.takeIf { it.targetId == operationTarget }?.let { WireGuardOperationStatus(it) }
                if (error.isNotBlank()) Text(error, style = LabTypography.Caption.copy(color = WireGuardRed))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = LabCoreSurface.InnerShape) { Text("取消", style = LabTypography.Button) }
                    OutlinedButton(onClick = {
                        val next = initial.copy(
                            name = name.trim().ifBlank { initial.name },
                            endpointSource = source,
                            endpointHost = host.trim(),
                            endpointPort = when {
                                source == WireGuardEndpointSource.DDNS && followsGatewayPort -> serverListenPort
                                else -> port.toIntOrNull()?.coerceIn(1, 65535) ?: DEFAULT_WIREGUARD_PORT
                            },
                            followsServerPort = source == WireGuardEndpointSource.DDNS && followsGatewayPort,
                            interfaceAddresses = splitWireGuardList(address),
                            dnsServers = splitWireGuardList(dns),
                            serverPublicKey = serverKey.trim(),
                            allowedIps = splitWireGuardList(allowedIps),
                            endpointBindingId = bindingId.trim(),
                        )
                        error = when {
                            automaticEndpointChanged -> "自动配置地址由 ${initial.endpointSource.displayName} 管理，请先转为手动配置"
                            source != WireGuardEndpointSource.STUN && next.endpointHost.isBlank() -> "请填写服务器地址"
                            source == WireGuardEndpointSource.MANUAL && next.serverPublicKey.isBlank() -> "请填写服务端公钥"
                            next.interfaceAddresses.isEmpty() -> "请填写客户端隧道地址"
                            next.allowedIps.isEmpty() -> "请填写路由网段"
                            else -> ""
                        }
                        if (error.isBlank()) {
                            val floor = operation?.completedVersion ?: 0L
                            if (onSave(next)) submittedAfterVersion = floor
                            else error = "已有网络配置操作正在进行，请稍候"
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, WireGuardBlue.copy(alpha = .48f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = WireGuardBlue), shape = LabCoreSurface.InnerShape) {
                        if (operation?.targetId == operationTarget && operation.running) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = WireGuardBlue)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (operation?.targetId == operationTarget && operation.running) "处理中…" else "保存", style = LabTypography.Button)
                    }
                }
                if (isExisting) {
                    TextButton(
                        onClick = {
                            val floor = operation?.completedVersion ?: 0L
                            if (onDelete()) submittedAfterVersion = floor
                            else error = "已有网络配置操作正在进行，请稍候"
                        },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.textButtonColors(contentColor = WireGuardRed)
                    ) {
                        if (operation?.targetId == operationTarget && operation.running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = WireGuardRed
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("正在删除配置…", style = LabTypography.CompactButton)
                        } else {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("删除此配置", style = LabTypography.CompactButton)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun WireGuardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    readOnly: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        readOnly = readOnly,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        textStyle = LabTypography.FieldValue,
        shape = LabCoreSurface.InnerShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = WireGuardBlue, focusedLabelColor = WireGuardBlue),
    )
}

private fun splitWireGuardList(value: String): List<String> = value.split(',', '\n').map(String::trim).filter(String::isNotBlank)

private fun formatWireGuardBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun formatHandshakeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "尚未握手"
    val diffSeconds = ((System.currentTimeMillis() - epochMillis) / 1000L).coerceAtLeast(0L)
    return when {
        diffSeconds < 5L -> "刚刚握手"
        diffSeconds < 60L -> "${diffSeconds} 秒前握手"
        diffSeconds < 3600L -> "${diffSeconds / 60L} 分钟前握手"
        else -> "${diffSeconds / 3600L} 小时前握手"
    }
}

private fun copyWireGuard(context: Context, label: String, value: String) {
    if (value.isBlank()) return
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
    toast(context, "已复制$label")
}

@Composable
private fun WireGuardServerSettingsDialog(
    initial: WireGuardServerConfig,
    operation: NetworkOperationState?,
    onDismiss: () -> Unit,
    onSave: (listenPort: Int, mtu: Int, address: String, enabled: Boolean) -> Boolean,
) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var port by remember { mutableStateOf(initial.listenPort.toString()) }
    var mtu by remember { mutableStateOf(initial.mtu.toString()) }
    var address by remember { mutableStateOf(initial.address) }
    var error by remember { mutableStateOf("") }
    var submittedAfterVersion by remember { mutableStateOf<Long?>(null) }
    val saving = operation?.running == true

    LaunchedEffect(operation?.completedVersion) {
        val floor = submittedAfterVersion ?: return@LaunchedEffect
        if (operation == null || operation.running || operation.completedVersion <= floor) return@LaunchedEffect
        submittedAfterVersion = null
        if (operation.error == null) onDismiss() else error = uiMessageZh(operation.error)
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = LabCoreSurface.CardShape, color = Color.White, shadowElevation = 10.dp) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("WireGuard 网关设置", style = LabTypography.CardTitle)
                Text(
                    "提交 Agent 服务端监听端口与 MTU；应用会等待 Agent 明确回执，并同步关联的 STUN 规则。",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted)
                )

                Surface(
                    shape = LabCoreSurface.InnerShape,
                    color = if (enabled) WireGuardBlue.copy(alpha = .06f) else WireGuardAmber.copy(alpha = .08f),
                    border = BorderStroke(1.dp, if (enabled) WireGuardBlue.copy(alpha = .18f) else WireGuardAmber.copy(alpha = .24f))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启用 WireGuard 服务端", style = LabTypography.FieldValue, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (enabled) "Agent 隧道内核运行中 (labwg0)" else "已停用，可避免与官方或其它服务端冲突",
                                style = LabTypography.Caption.copy(color = if (enabled) LabV2.InkMuted else WireGuardAmber)
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = !saving,
                            onCheckedChange = { enabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = WireGuardBlue,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = LabV2.BorderStrong
                            )
                        )
                    }
                }

                WireGuardField(port, { port = it.filter(Char::isDigit) }, "服务端监听端口（默认 51820）", KeyboardType.Number, enabled = !saving)
                WireGuardField(mtu, { mtu = it.filter(Char::isDigit) }, "接口 MTU（默认 1420，推荐 1280~1500）", KeyboardType.Number, enabled = !saving)
                WireGuardField(address, { address = it }, "服务端虚拟网段（默认 10.77.0.1/24）", enabled = !saving)
                operation?.let { WireGuardOperationStatus(it) }
                if (error.isNotBlank()) Text(error, style = LabTypography.Caption.copy(color = WireGuardRed))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = LabCoreSurface.InnerShape
                    ) {
                        Text("取消", style = LabTypography.Button)
                    }
                    Button(
                        onClick = {
                            if (saving) return@Button
                            val portInt = port.toIntOrNull()
                            val mtuInt = mtu.toIntOrNull()
                            when {
                                portInt == null || portInt !in 1..65535 -> error = "端口必须在 1~65535 之间"
                                mtuInt == null || mtuInt !in 1280..1500 -> error = "MTU 必须在 1280~1500 之间"
                                address.isBlank() -> error = "请填写服务端虚拟网段"
                                else -> {
                                    error = ""
                                    val floor = operation?.completedVersion ?: 0L
                                    if (onSave(portInt, mtuInt, address, enabled)) submittedAfterVersion = floor
                                    else error = "已有网络配置操作正在进行，请稍候"
                                }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColors(containerColor = WireGuardBlue),
                        shape = LabCoreSurface.InnerShape
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在保存并同步…", style = LabTypography.Button)
                        } else {
                            Text("保存并同步", style = LabTypography.Button)
                        }
                    }
                }
            }
        }
    }
}


