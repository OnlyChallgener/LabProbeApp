package com.labprobe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync

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
private fun wireGuardPeerTarget(peerId: String) = "wireguard:peer:$peerId"

/** The shared operation registry also serves STUN and favorites; WG never renders their terminal state. */
internal fun isWireGuardOperationTarget(targetId: String): Boolean =
    targetId == WIREGUARD_GATEWAY_TARGET || targetId == WIREGUARD_SYNC_TARGET ||
        targetId.startsWith("wireguard:profile:") || targetId.startsWith("wireguard:connect:") ||
        targetId.startsWith("wireguard:peer:")

/** Only rules that the core binding API will accept are offered as a WG binding choice. */
internal fun selectableWireGuardStunRules(
    rules: List<StunRule>,
    listenPort: Int,
    routerIp: String,
): List<StunRule> = rules.filter { rule ->
    rule.enabled && rule.serviceType.equals("WireGuard", ignoreCase = true) &&
        rule.targetPort == listenPort && isWireGuardStunTarget(rule, routerIp)
}

internal fun wireGuardServerConfigMatchesDesired(
    actual: WireGuardServerConfig,
    desired: WireGuardServerConfig,
): Boolean = actual.listenPort == desired.listenPort && actual.mtu == desired.mtu &&
    actual.address == desired.address && actual.enabled == desired.enabled

private fun shouldAutoDismissWireGuardMessage(value: String): Boolean =
    value.isNotBlank() && value.contains("已") &&
        listOf("失败", "不可用", "待同步", "待确认", "待核对", "尚未", "未确认", "错误").none(value::contains)

private fun wireGuardMessageColor(value: String): Color = when {
    value.contains("待核对") || value.contains("已提交") -> WireGuardAmber
    value.contains("失败") || value.contains("未更改") || value.contains("不可用") -> WireGuardRed
    else -> LabV2.InkMuted
}

internal fun wireGuardRemoteMutationStatus(
    action: String,
    result: WireGuardRemoteMutationResult<*>,
): String = when (result) {
    is WireGuardRemoteMutationResult.Applied<*> -> "${action}已由 Agent 确认"
    is WireGuardRemoteMutationResult.PendingVerification -> if (result.submittedRevision == null) {
        "${action}提交结果待核对；原本地配置保持不变。${result.message}"
    } else {
        "${action}已提交，待核对；原本地配置保持不变。${result.message}"
    }
    is WireGuardRemoteMutationResult.NotSubmitted -> "${action}失败，未更改：${result.message}"
}

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
    val sharedOperation by operations.state.collectAsState()
    val operation = sharedOperation?.takeIf { isWireGuardOperationTarget(it.targetId) }
    var dismissedOperationVersion by remember { mutableStateOf(0L) }
    val visibleOperation = operation?.takeIf {
        it.running || it.completedVersion > dismissedOperationVersion
    }
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
    var gatewayVerificationPending by remember { mutableStateOf(false) }
    var pendingGatewayConfig by remember { mutableStateOf<WireGuardServerConfig?>(null) }
    var gatewaySubmissionConfirmed by remember { mutableStateOf(false) }
    var gatewayOperationMode by remember { mutableStateOf("save") }
    var gatewayAppliedThisAttempt by remember { mutableStateOf(false) }
    /** Timestamp of the moment a managed tunnel came up without any handshake. */
    var handshakeWatchSince by remember { mutableStateOf(0L) }
    val handshaked = runtime.running && runtime.latestHandshakeAt > 0L &&
        System.currentTimeMillis() - runtime.latestHandshakeAt < 180_000L
    // 打通 CGNAT 的第一包 UDP 常会丢，wg 后端要重发几次 handshake initiation 才成；
    // 15 秒就报「一直握不上手」等于在用户刚点连接时就判死，这里留 30 秒余量。
    val noHandshakeTooLong = handshakeWatchSince > 0L && System.currentTimeMillis() - handshakeWatchSince > 30_000L
    val homeLanRoutes = homeLanRouteCidrs(prefs.wgHomeLanIpv4, prefs.routerLanUrl)
    var routerPeers by remember { mutableStateOf<List<WireGuardRouterPeer>>(emptyList()) }
    var routerPeersAt by remember { mutableStateOf(0L) }
    var routerPeersOffline by remember { mutableStateOf(false) }
    var showRouterPeers by remember { mutableStateOf(false) }
    var routerPeersTick by remember { mutableStateOf(0) }

    suspend fun refreshRouterPeers() {
        runCatching { wireGuardHubApi.loadRouterPeers() }
            .onSuccess { peers ->
                routerPeers = peers
                routerPeersAt = System.currentTimeMillis()
                routerPeersOffline = false
                withContext(Dispatchers.IO) {
                    prefs.wgRouterPeersJson = routerPeersToJson(peers)
                    prefs.wgRouterPeersAt = System.currentTimeMillis()
                }
            }
            .onFailure {
                routerPeersOffline = true
                // 离线时仍要能翻历史：读上次成功拉到的快照，删除按钮整排禁用。
                val cached = withContext(Dispatchers.IO) { decodeRouterPeers(prefs.wgRouterPeersJson) }
                if (cached.isNotEmpty()) {
                    routerPeers = cached
                    routerPeersAt = prefs.wgRouterPeersAt
                }
            }
    }

    LaunchedEffect(showRouterPeers, routerPeersTick) {
        if (showRouterPeers) refreshRouterPeers()
    }
    val gatewayErrorPrefix = when {
        operation?.targetId != WIREGUARD_GATEWAY_TARGET || operation.error == null -> null
        gatewayOperationMode == "refresh" -> "刷新失败"
        gatewayAppliedThisAttempt -> "网关已应用；后续处理失败"
        else -> "提交失败，未更改"
    }

    fun dismissOperationStatus() {
        operation?.takeIf { !it.running }?.let {
            if (it.error != null) operations.acknowledgeCompletedError("wireguard:")
            dismissedOperationVersion = maxOf(dismissedOperationVersion, it.completedVersion)
        }
    }

    fun refreshGatewayVerification() {
        gatewayOperationMode = "refresh"
        gatewayAppliedThisAttempt = false
        val launched = operations.launch(WIREGUARD_GATEWAY_TARGET, "正在刷新网关状态…") { report ->
            val state = wireGuardHubApi.loadServerState()
            serverConfig = state.config
            val desired = pendingGatewayConfig
            val hubMatchesDesired = desired == null || wireGuardServerConfigMatchesDesired(state.config, desired)
            if (!hubMatchesDesired) {
                gatewayVerificationPending = true
                gatewaySubmissionConfirmed = false
                message = "Hub 当前设置与待核对修改不一致；此前修改尚未确认生效，请检查 Hub/代理后再重试。"
                report("Hub 当前设置与待核对修改不一致")
            } else if (isWireGuardServerConfigApplied(state, state.config)) {
                gatewayVerificationPending = false
                pendingGatewayConfig = null
                gatewaySubmissionConfirmed = true
                message = "WireGuard 网关已由 Agent 确认"
                report("WireGuard 网关已由 Agent 确认")
            } else {
                gatewayVerificationPending = true
                gatewaySubmissionConfirmed = true
                message = "Hub 已保存网关设置，但 Agent 尚未确认应用；请稍后刷新核对。"
                report("Hub 已保存，仍待 Agent 确认")
            }
        }
        if (!launched) message = "已有网络配置操作正在进行，请稍候"
    }

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

    LaunchedEffect(operation?.targetId, operation?.completedVersion, operation?.running, operation?.error) {
        val completed = operation ?: return@LaunchedEffect
        if (completed.running || completed.error != null || completed.completedVersion <= dismissedOperationVersion) return@LaunchedEffect
        delay(2_500L)
        val current = operations.state.value
        if (current?.targetId == completed.targetId && current.completedVersion == completed.completedVersion && !current.running) {
            dismissedOperationVersion = maxOf(dismissedOperationVersion, completed.completedVersion)
        }
    }

    LaunchedEffect(message) {
        val currentMessage = message
        if (!shouldAutoDismissWireGuardMessage(currentMessage)) return@LaunchedEffect
        delay(3_500L)
        if (message == currentMessage) message = ""
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
        if (!startCheckInProgress && sharedOperation?.running != true) requestedStart = profile
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
                    // 只挡「结构不完整」：绑定身份为空说明这条配置根本没跟任何 STUN 规则挂上，
                    // 中继侧（labrelay wireguard.rs）也会拒收。
                    // 但 endpointUpdateError 不挡 —— 它只代表「这次刷新状态没成功」，地址本身是
                    // 上次 STUN 成功时写进来的可用值；拿它拦连接等于让一次瞬时失败锁死入口，
                    // 而且它会持久化到下次同步成功才清（WireGuardClient.kt:293）。
                    require(profile.endpointBindingId.isNotBlank()) {
                        "STUN 绑定尚未就绪，请先保存并同步配置"
                    }
                }
                // 网关状态不再预检：Hub/Agent 的能力快照会把「探测不到」报成「没就绪」，
                // 用它拦连接就是把一个坏信号变成连不上的理由。直接连，握手结果自己会说话。
                report("正在启动 ${profile.name}…")
                val started = startClient(profile)
                report(if (started) "${profile.name} 已启动" else "等待系统 VPN 授权")
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
            val managedTunnel = runtime.running &&
                profiles.firstOrNull { it.id == runtime.profileId }?.endpointSource != WireGuardEndpointSource.MANUAL
            val handshakeFresh = runtime.latestHandshakeAt > 0L &&
                System.currentTimeMillis() - runtime.latestHandshakeAt < 180_000L
            handshakeWatchSince = when {
                !managedTunnel || handshakeFresh -> 0L
                else -> if (handshakeWatchSince > 0L) handshakeWatchSince else System.currentTimeMillis()
            }
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
    // 循环内部每次都从 store 重新读，所以键里不能再放 profiles：写回一次 endpointRevision
    // 就会重启循环并立刻再打一轮 Hub，Hub 日志里的「每秒三次」就是这么来的。
    LaunchedEffect(labProbeDdns.updatedAt, nativeDdns.updatedAt, sharedOperation?.running) {
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
    LaunchedEffect(sharedOperation?.completedVersion) {
        val stunApi = StunApi(prefs)
        while (true) {
            if (operations.state.value?.running == true) {
                delay(1_000L)
                continue
            }
            val operationVersion = operations.state.value?.completedVersion ?: 0L
            val current = withContext(Dispatchers.IO) { store.load() }
            if (current.any { it.endpointSource == WireGuardEndpointSource.STUN }) {
                // 这一页不再判定「STUN 地址能不能用」：读不到规则就什么都不做，
                // 地址通不通由连接结果说。以前 Hub 一抖，正在工作的好地址也被刷成「不可用」。
                val snapshotResult = runCatching { stunApi.list() }
                if (snapshotResult.isSuccess) {
                    val snapshot = snapshotResult.getOrThrow()
                    val probed = runCatching {
                        wireGuardHubApi.loadServerConfig() to wireGuardHubApi.loadRouterLanIp()
                    }.getOrNull()
                    val currentServerConfig = probed?.first ?: serverConfig
                    val routerIp = probed?.second?.takeIf { it.isNotBlank() } ?: prefs.wgHomeLanIpv4
                    if (routerIp.isNotBlank() && prefs.wgHomeLanIpv4 != routerIp) prefs.wgHomeLanIpv4 = routerIp
                    val stale = operations.state.value?.running == true ||
                        operations.state.value?.completedVersion != operationVersion
                    if (snapshot.rulesLoaded && !stale) {
                        serverConfig = currentServerConfig
                        stunRules = selectableWireGuardStunRules(snapshot.rules, currentServerConfig.listenPort, routerIp)
                        val updated = withContext(Dispatchers.IO) {
                            if (operations.state.value?.running == true || operations.state.value?.completedVersion != operationVersion) {
                                null
                            } else {
                                WireGuardEndpointCoordinator.applyStunSnapshot(
                                    store,
                                    current,
                                    snapshot.rules,
                                    currentServerConfig.listenPort,
                                    routerIp,
                                )
                                store.load()
                            }
                        }
                        if (updated != null) {
                            updated.filter { it.endpointSource == WireGuardEndpointSource.STUN }.forEach { profile ->
                                applyManagedEndpoint(profile, current.firstOrNull { it.id == profile.id }?.endpoint.orEmpty())
                            }
                            reload()
                        }
                    }
                }
            } else {
                runCatching {
                    Triple(stunApi.list(), wireGuardHubApi.loadServerConfig(), wireGuardHubApi.loadRouterLanIp())
                }.onSuccess { (snapshot, currentServerConfig, routerIp) ->
                    // 路由器 LAN 一旦学到就存下来：隧道该路由哪个内网网段靠它，而不是猜。
                    if (routerIp.isNotBlank() && prefs.wgHomeLanIpv4 != routerIp) prefs.wgHomeLanIpv4 = routerIp
                    if (!snapshot.rulesLoaded) return@onSuccess
                    serverConfig = currentServerConfig
                    stunRules = selectableWireGuardStunRules(snapshot.rules, currentServerConfig.listenPort, routerIp)
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
        val isHandshaked = handshaked
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
            Text(
                if (homeLanRoutes.isEmpty()) "家庭内网网段还没学到：连着 Hub 打开本页就会自动识别并加入路由。"
                else "自动路由家庭内网 ${homeLanRoutes.joinToString("、")}（取自路由器实测地址）",
                style = LabTypography.Caption.copy(color = if (homeLanRoutes.isEmpty()) WireGuardAmber else LabV2.InkMuted),
            )
            if (noHandshakeTooLong && runningProfile != null) {
                Surface(
                    color = WireGuardAmber.copy(alpha = .07f),
                    shape = LabCoreSurface.InnerShape,
                    border = BorderStroke(1.dp, WireGuardAmber.copy(alpha = .28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "隧道起来但 ${runningProfile.name} 一直握不上手：公网地址或路由器网关可能没在收包。",
                            Modifier.weight(1f),
                            style = LabTypography.Caption.copy(color = WireGuardAmber),
                            maxLines = 2,
                        )
                        TextButton(
                            onClick = { pendingServerEnable = runningProfile },
                            enabled = sharedOperation?.running != true,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("启用网关", style = LabTypography.CompactButton.copy(color = LabV2.Primary))
                        }
                    }
                }
            }
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
                        enabled = sharedOperation?.running != true,
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
                        operation = visibleOperation?.takeIf { it.targetId.endsWith(":${profile.id}") },
                        onDismissOperation = ::dismissOperationStatus,
                        actionsEnabled = sharedOperation?.running != true,
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
            WireGuardCreateCard(WireGuardEndpointSource.MANUAL, sharedOperation?.running != true) {
                editor = WireGuardProfile.newProfile(WireGuardEndpointSource.MANUAL)
                editingExisting = false
            }
        }
        if (profiles.none { it.endpointSource == WireGuardEndpointSource.DDNS }) {
            WireGuardCreateCard(WireGuardEndpointSource.DDNS, sharedOperation?.running != true) {
                editor = WireGuardProfile.newProfile(WireGuardEndpointSource.DDNS).copy(
                    endpointPort = serverConfig.listenPort,
                    followsServerPort = true,
                )
                editingExisting = false
            }
        }
        if (profiles.none { it.endpointSource == WireGuardEndpointSource.STUN }) {
            WireGuardCreateCard(WireGuardEndpointSource.STUN, sharedOperation?.running != true) {
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
            enabled = sharedOperation?.running != true,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, WireGuardBlue.copy(alpha = .32f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WireGuardBlue),
            shape = LabCoreSurface.InnerShape,
        ) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (operation?.targetId == WIREGUARD_SYNC_TARGET && operation?.running == true) "正在同步 Agent…" else "重新同步自动配置", style = LabTypography.CompactButton)
        }
        OutlinedButton(
            onClick = { showRouterPeers = true },
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, WireGuardBlue.copy(alpha = .32f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WireGuardBlue),
            shape = LabCoreSurface.InnerShape,
        ) {
            Icon(Icons.Rounded.Public, null, Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text("路由器上的隧道", style = LabTypography.CompactButton)
        }
        // 同一条操作只播报一次：属于某个配置卡片的由那张卡片自己显示，
        // 否则一条「配置已提交，待核对」会在卡里和页面底部各出现一次。
        visibleOperation?.takeIf { state ->
            editor == null && !state.targetId.startsWith("wireguard:peer:") &&
                profiles.none { state.targetId == wireGuardProfileTarget(it.id) }
        }?.let {
            WireGuardOperationStatus(
                operation = it,
                errorPrefix = gatewayErrorPrefix,
                onDismiss = ::dismissOperationStatus,
            )
        }
        // 系统级「始终开启」才是真自动：App 自己在后台拉起 VPN 会被 Android 拦掉，而家里
        // 在 CGNAT 后面、打洞会掉，所以这条引导比自研重连循环有用得多。
        OutlinedButton(
            onClick = {
                // 用字符串常量：本文件已把 Icons.Rounded.Settings 导入成 `Settings`，
                // 再引 android.provider.Settings 会撞名。
                runCatching {
                    context.startActivity(
                        Intent("android.settings.VPN_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, WireGuardBlue.copy(alpha = .32f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WireGuardBlue),
            shape = LabCoreSurface.InnerShape,
        ) {
            Icon(Icons.Rounded.Shield, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.fillMaxWidth()) {
                Text("断线自动重连：去系统开「始终开启 VPN」", style = LabTypography.CompactButton)
                Text(
                    "开机自连、断线由系统重连、切 Wi‑Fi / 4G 自动恢复。别勾「禁止未受保护的应用」—— 隧道起不来时那会让整机没网。",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                    maxLines = 3,
                )
            }
        }
        if (gatewayVerificationPending) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = WireGuardAmber.copy(alpha = .08f),
                shape = LabCoreSurface.InnerShape,
                border = BorderStroke(1.dp, WireGuardAmber.copy(alpha = .24f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (gatewaySubmissionConfirmed) {
                            "Hub 已保存网关设置，等待 Agent 确认应用。"
                        } else {
                            "网关提交结果尚不确定，等待 Hub/代理恢复后核对；请勿重复提交。"
                        },
                        modifier = Modifier.weight(1f),
                        style = LabTypography.Caption.copy(color = WireGuardAmber),
                    )
                    TextButton(onClick = ::refreshGatewayVerification, enabled = sharedOperation?.running != true) {
                        Text("刷新核对", style = LabTypography.CompactButton.copy(color = WireGuardAmber))
                    }
                }
            }
        }
        if (message.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = LabTypography.Caption.copy(color = wireGuardMessageColor(message)),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { message = "" }) {
                    Text("关闭", style = LabTypography.CompactButton.copy(color = LabV2.InkMuted))
                }
            }
        }
    }

    if (showServerSettings) {
        WireGuardServerSettingsDialog(
            initial = serverConfig,
            operation = visibleOperation?.takeIf { it.targetId == WIREGUARD_GATEWAY_TARGET },
            onDismissOperation = ::dismissOperationStatus,
            errorPrefix = gatewayErrorPrefix,
            onDismiss = { showServerSettings = false },
            onSave = { listenPort, mtu, address, enabled ->
                gatewayOperationMode = "save"
                gatewayAppliedThisAttempt = false
                gatewayVerificationPending = false
                pendingGatewayConfig = null
                gatewaySubmissionConfirmed = false
                operations.launch(WIREGUARD_GATEWAY_TARGET, "正在准备网关修改…") { report ->
                    val freshProfiles = withContext(Dispatchers.IO) { store.load() }
                    val result = try {
                        wireGuardHubApi.updateServerConfigAndSync(
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
                    } catch (pending: WireGuardPendingVerificationException) {
                        gatewayVerificationPending = true
                        pendingGatewayConfig = serverConfig.copy(
                            listenPort = listenPort,
                            mtu = mtu,
                            address = address.trim().ifBlank { "10.77.0.1/24" },
                            enabled = enabled,
                        )
                        gatewaySubmissionConfirmed = pending.submittedRevision != null
                        message = pending.message.orEmpty().ifBlank {
                            "WireGuard 网关修改结果待核对；请勿重复提交。"
                        }
                        report("网关修改结果待核对；请等待服务恢复后刷新")
                        return@launch
                    }
                    if (!result.applied) {
                        serverConfig = result.config
                        gatewayVerificationPending = true
                        pendingGatewayConfig = result.config
                        gatewaySubmissionConfirmed = true
                        message = "网关设置已提交，但 Agent/上游服务暂不可用或尚未确认；本机配置保持不变，请刷新核对。"
                        report("网关已提交，待 Agent/上游确认；本机配置保持不变")
                        return@launch
                    }
                    gatewayAppliedThisAttempt = true
                    pendingGatewayConfig = null
                    gatewaySubmissionConfirmed = true
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
                            require(profile.endpointBindingId.isNotBlank()) { "STUN 绑定尚未就绪，请先保存并同步配置" }
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

    if (showRouterPeers) {
        WireGuardRouterPeersDialog(
            peers = routerPeers,
            snapshotAt = routerPeersAt,
            offline = routerPeersOffline,
            operation = visibleOperation?.takeIf { it.targetId.startsWith("wireguard:peer:") },
            onDelete = { peer ->
                val started = operations.launch(wireGuardPeerTarget(peer.id), "正在删除 ${peer.name.ifBlank { peer.id }}…") { report ->
                    report("正在从路由器移除这条隧道…")
                    wireGuardHubApi.removeRouterPeer(peer.id)
                    refreshRouterPeers()
                    report("已从路由器移除 ${peer.name.ifBlank { peer.id }}")
                }
                if (!started) message = "已有操作正在进行，请稍候"
            },
            onRefresh = { routerPeersTick++ },
            onDismiss = { showRouterPeers = false },
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
            homeLanRoutes = homeLanRoutes,
            operation = visibleOperation,
            onDismissOperation = ::dismissOperationStatus,
            onDismiss = { editor = null },
            onDelete = {
                // 只动本机：停隧道、删卡片和私钥。路由器上的 peer 是共享配置，
                // 不在手机卡片里拆 —— 官方 WireGuard 客户端的删除也是这个语义。
                operations.launch(wireGuardProfileTarget(profile.id), "正在删除 ${profile.name}…") { report ->
                    if (runtime.profileId == profile.id) {
                        report("正在停止 ${profile.name}…")
                        runtime = controller.stop()
                    }
                    withContext(Dispatchers.IO) { store.delete(profile.id) }
                    reload()
                    report("已删除 ${profile.name}")
                }
            },
            onCopyClientKey = { copyWireGuard(context, "WireGuard 客户端公钥", clientPub) },
            onSave = { edited ->
                val existingAtLaunch = editingExisting
                operations.launch(wireGuardProfileTarget(edited.id), "正在保存 ${edited.name}…") { report ->
                    val original = if (existingAtLaunch) withContext(Dispatchers.IO) {
                        store.load().firstOrNull { it.id == edited.id }
                            ?: throw IllegalStateException("配置已被删除，请刷新后重新新增")
                    } else null
                    val next = editedWireGuardProfile(original, edited)

                    if (original != null && original.endpointSource != WireGuardEndpointSource.MANUAL) {
                        report("正在提交 ${original.name} 的远端变更…")
                        val publicKey = withContext(Dispatchers.IO) { wireGuardPublicKey(store.privateKey(original.id)) }
                        when (val result = wireGuardHubApi.transitionAutomaticProfile(original, next, publicKey)) {
                            is WireGuardRemoteMutationResult.Applied -> {
                                withContext(Dispatchers.IO) { store.saveProfileEdit(result.value.profile) }
                                reload()
                                report(
                                    if (next.endpointSource == WireGuardEndpointSource.MANUAL) {
                                        "Agent 已确认移除自动引用，已切换为手动配置"
                                    } else {
                                        "${result.value.profile.name} 已由 Agent 确认应用"
                                    }
                                )
                            }
                            is WireGuardRemoteMutationResult.PendingVerification -> {
                                message = wireGuardRemoteMutationStatus("配置", result)
                                report("配置已提交，待核对；原本地配置保持不变")
                            }
                            is WireGuardRemoteMutationResult.NotSubmitted -> {
                                throw IllegalStateException(wireGuardRemoteMutationStatus("保存", result))
                            }
                        }
                        return@launch
                    }

                    withContext(Dispatchers.IO) {
                        if (existingAtLaunch) store.saveProfileEdit(next) else store.create(next)
                    }
                    reload()
                    if (next.endpointSource == WireGuardEndpointSource.MANUAL) {
                        report(if (existingAtLaunch) "手动配置已保存" else "已创建手动配置，并生成客户端密钥")
                    } else {
                        report("配置已保存，正在同步 Agent…")
                        val saved = withContext(Dispatchers.IO) { store.load().first { it.id == next.id } }
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
    onDismissOperation: () -> Unit = {},
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
            // 发出去了却一个回包都没有 = 连不上，不是还在连；红叉比一直转圈诚实。
            val connectFailed = runtime.lastError.isNotBlank() ||
                (runtime.latestHandshakeAt == 0L && runtime.sentBytes > 0L)
            Surface(
                color = when {
                    isHandshaked -> WireGuardGreen.copy(alpha = .08f)
                    connectFailed -> WireGuardRed.copy(alpha = .07f)
                    else -> WireGuardAmber.copy(alpha = .08f)
                },
                shape = LabCoreSurface.InnerShape,
                border = BorderStroke(1.dp, when {
                    isHandshaked -> WireGuardGreen.copy(alpha = .24f)
                    connectFailed -> WireGuardRed.copy(alpha = .24f)
                    else -> WireGuardAmber.copy(alpha = .24f)
                }),
            ) {

                Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = if (isHandshaked) WireGuardGreen else if (connectFailed) WireGuardRed else WireGuardAmber
                    when {
                        isHandshaked -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(15.dp), tint = statusColor)
                        connectFailed -> Icon(Icons.Rounded.Cancel, null, Modifier.size(15.dp), tint = statusColor)
                        else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = statusColor)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            isHandshaked -> "已握手 (${formatHandshakeTime(runtime.latestHandshakeAt)})"
                            connectFailed -> "连接失败 (未收到服务端回包)"
                            else -> "正在握手 (等待服务端响应…)"
                        },
                        Modifier.weight(1f),
                        style = LabTypography.Caption.copy(color = statusColor),
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
        operation?.let { WireGuardOperationStatus(it, onDismiss = onDismissOperation) }
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
                val starting = operation?.running == true
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    // 不再用 endpointUpdateError 拦「启动连接」：那只代表这次刷新状态没成功，
                    // 地址是上次 STUN 成功时写入的可用值。地址齐不齐由 isComplete 管。
                    enabled = actionsEnabled && profile.isComplete && !starting,
                    shape = LabCoreSurface.InnerShape
                ) {
                    if (starting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    // 按钮自己说进度：以前它两秒不动，状态文字在卡片上方/屏幕外，看起来像没反应。
                    Text(
                        if (starting) "连接中…" else "启动连接",
                        style = LabTypography.CompactButton
                    )
                }
            }
        }
    }
}

@Composable
private fun WireGuardOperationStatus(
    operation: NetworkOperationState,
    errorPrefix: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
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
                operation.error?.let { error ->
                    listOfNotNull(errorPrefix, uiMessageZh(error)).joinToString("：")
                } ?: operation.label,
                modifier = Modifier.weight(1f),
                style = LabTypography.Caption.copy(color = color),
                maxLines = 2,
            )
            if (!operation.running && onDismiss != null) {
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("关闭", style = LabTypography.CompactButton.copy(color = color))
                }
            }
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
    homeLanRoutes: List<String> = emptyList(),
    operation: NetworkOperationState?,
    onDismissOperation: () -> Unit,
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
    /** 删除是多步远端操作，浮窗要一直说清走到哪一步（卡片底部那行字常被滚出屏幕）。 */
    var deleteInFlight by remember(initial.id) { mutableStateOf(false) }
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
        deleteInFlight = false
        if (operation.error == null) onDismiss() else error = uiMessageZh(operation.error)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.93f).heightIn(max = 720.dp),
            shape = LabV2.CardShape,
            color = Color.White,
        ) {
            Box(Modifier.fillMaxSize()) {
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
                                availableStunRules.forEach { rule ->
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
                                if (isFullTunnel) allowedIps = (listOf("10.77.0.0/24") + homeLanRoutes).distinct().joinToString(", ")
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
                if (homeLanRoutes.isNotEmpty()) {
                    Text(
                        "连接时自动加入家庭内网：${homeLanRoutes.joinToString("、")}",
                        style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                    )
                }
                WireGuardField(dns, { dns = it }, "隧道 DNS（可选）")
                // 删除进行中由底部浮窗独占播报，否则同一句话会出现三份进度圈。
                operation?.takeIf { it.targetId == operationTarget && !deleteInFlight }?.let {
                    WireGuardOperationStatus(it, onDismiss = onDismissOperation)
                }
                if (error.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), style = LabTypography.Caption.copy(color = WireGuardRed))
                        TextButton(onClick = { error = "" }) {
                            Text("知道了", style = LabTypography.CompactButton.copy(color = WireGuardRed))
                        }
                    }
                }
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
                        if (operation?.targetId == operationTarget && operation.running && !deleteInFlight) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = WireGuardBlue)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (operation?.targetId == operationTarget && operation.running && !deleteInFlight) "处理中…" else "保存", style = LabTypography.Button)
                    }
                }
                // 删除只动本机：官方 WireGuard 客户端的删除就是这个语义，
                // 路由器上的 peer 由 Hub 那侧管理，不在手机卡片里拆共享链路。
                if (isExisting) {
                    TextButton(
                        onClick = {
                            val floor = operation?.completedVersion ?: 0L
                            if (onDelete()) { submittedAfterVersion = floor; deleteInFlight = true }
                            else error = "已有网络配置操作正在进行，请稍候"
                        },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.textButtonColors(contentColor = WireGuardRed),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除配置", style = LabTypography.CompactButton)
                    }
                }
            }
                if (deleteInFlight) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                        shape = LabCoreSurface.InnerShape,
                        color = Color.White,
                        border = BorderStroke(1.dp, WireGuardRed.copy(alpha = .38f)),
                        shadowElevation = 10.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = WireGuardRed)
                            Text(
                                operation?.takeIf { it.targetId == operationTarget }?.label ?: "正在删除配置…",
                                style = LabTypography.Caption.copy(color = LabV2.Ink, fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun WireGuardRouterPeersDialog(
    peers: List<WireGuardRouterPeer>,
    snapshotAt: Long,
    offline: Boolean,
    operation: NetworkOperationState?,
    onDelete: (WireGuardRouterPeer) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stamp = snapshotAt.takeIf { it > 0L }?.let {
        runCatching {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
        }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.93f).heightIn(max = 640.dp),
            shape = LabV2.CardShape,
            color = Color.White,
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("路由器上的隧道", style = LabTypography.PageTitle)
                Text(
                    "删手机上的配置不会动这里；这里删的是路由器共享的隧道，会真的断掉那一头的连接。",
                    style = LabTypography.Caption.copy(color = LabV2.InkMuted),
                )
                Text(
                    when {
                        offline && stamp != null -> "离线快照 $stamp · 连上 Hub 才能删除"
                        offline -> "还没成功连上 Hub 读取过"
                        stamp != null -> "实时 · $stamp · 共 ${peers.size} 条"
                        else -> "实时"
                    },
                    style = LabTypography.Caption.copy(
                        color = if (offline) WireGuardAmber else LabV2.InkMuted,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                if (peers.isEmpty()) {
                    Text("路由器上目前没有 WireGuard 隧道。", style = LabTypography.Caption.copy(color = LabV2.InkMuted))
                }
                peers.forEach { peer ->
                    val label = peer.name.ifBlank { peer.id }
                    val peerOperation = operation?.takeIf { it.targetId == wireGuardPeerTarget(peer.id) }
                    val busy = peerOperation?.running == true
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = LabCoreSurface.Inner,
                        shape = LabCoreSurface.InnerShape,
                        border = BorderStroke(1.dp, if (peer.referenced) LabCoreSurface.Border else WireGuardAmber.copy(alpha = .32f)),
                    ) {
                        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, style = LabTypography.CardTitle.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${peer.allowedIps.joinToString(" ")} · ${peer.publicKey.take(8)}… · " +
                                            if (peer.referenced) "有对应配置" else "无对应配置（孤儿）",
                                        style = LabTypography.Caption.copy(color = if (peer.referenced) LabV2.InkMuted else WireGuardAmber),
                                        maxLines = 2,
                                    )
                                }
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = LabV2.Primary)
                                } else {
                                    TextButton(
                                        onClick = { onDelete(peer) },
                                        enabled = !offline && operation?.running != true,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = WireGuardRed),
                                    ) { Text("删除", style = LabTypography.CompactButton) }
                                }
                            }
                            peerOperation?.let { state ->
                                Text(
                                    state.error?.let { uiMessageZh(it) } ?: state.label,
                                    style = LabTypography.Caption.copy(
                                        color = if (state.error != null) WireGuardRed else LabV2.Primary,
                                    ),
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = operation?.running != true,
                        modifier = Modifier.weight(1f),
                        shape = LabCoreSurface.InnerShape,
                    ) { Text(if (offline) "重试连接 Hub" else "刷新", style = LabTypography.Button) }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = LabCoreSurface.InnerShape,
                    ) { Text("关闭", style = LabTypography.Button) }
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
    onDismissOperation: () -> Unit,
    errorPrefix: String?,
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
                    "提交 Agent 服务端监听端口与 MTU；应用会等待 Agent 明确回执，并同步关联的 STUN 规则。停用仅停止服务端，不会解绑或删除 STUN 规则。",
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
                                if (enabled) "Agent 隧道内核运行中 (labwg0)" else "已停用服务端；不会解绑或删除已关联的 STUN 规则",
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
                operation?.let { WireGuardOperationStatus(it, errorPrefix = errorPrefix, onDismiss = onDismissOperation) }
                if (error.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), style = LabTypography.Caption.copy(color = WireGuardRed))
                        TextButton(onClick = { error = "" }) {
                            Text("知道了", style = LabTypography.CompactButton.copy(color = WireGuardRed))
                        }
                    }
                }
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


