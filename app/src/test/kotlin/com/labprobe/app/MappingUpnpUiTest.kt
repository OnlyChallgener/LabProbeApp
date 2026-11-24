package com.labprobe.app

import android.content.Context
import com.sun.net.httpserver.HttpServer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class MappingUpnpUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: HttpServer

    @After fun stopServer() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun device(
        name: String,
        mac: String,
        ip: String,
        manufacture: String = "UGREEN",
        devType: String = "nas",
    ) = DeviceItem(
        name = name, mac = mac, online = true, ip = ip, ssid = "", band = "", rssi = "", rxrate = "",
        onlineSince = "", offlineAt = "", onlineDurationText = "", lastSeenAt = "",
        manufacture = manufacture, devType = devType,
    )

    private fun render(
        workspaceId: String,
        rule: PortMapRule,
        devices: List<DeviceItem>,
        seedNativeAndUpnp: Boolean = false,
    ) {
        val context = RuntimeEnvironment.getApplication()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/portmaps") { exchange ->
            val runtime = JSONObject()
                .put("state", rule.runtime.state)
                .put("resolvedTarget", rule.runtime.resolvedTarget)
                .put("activeConnections", rule.runtime.activeConnections)
                .put("activePeers", rule.runtime.activePeers)
                .put("totalUploadBytes", rule.runtime.totalUploadBytes)
                .put("totalDownloadBytes", rule.runtime.totalDownloadBytes)
                .put("totalUploadPackets", rule.runtime.totalUploadPackets)
                .put("totalDownloadPackets", rule.runtime.totalDownloadPackets)
                .put("startedAt", rule.runtime.startedAt ?: JSONObject.NULL)
                .put("expiresAt", rule.runtime.expiresAt ?: rule.expiresAt ?: JSONObject.NULL)
                .put("lastResolvedAt", rule.runtime.lastResolvedAt ?: JSONObject.NULL)
                .put("lastError", rule.runtime.lastError)
            val row = JSONObject()
                .put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled)
                .put("mode", rule.mode).put("listenPort", rule.listenPort)
                .put("targetType", rule.targetType).put("targetMode", rule.targetMode)
                .put("targetIpv4", rule.targetIpv4).put("targetIpv6", rule.targetIpv6)
                .put("targetIpv6Snapshot", rule.targetIpv6Snapshot).put("targetIpv6Suffix", rule.targetIpv6Suffix)
                .put("targetMac", rule.targetMac).put("targetPort", rule.targetPort)
                .put("serviceType", rule.serviceType).put("transportProtocol", rule.transportProtocol)
                .put("preferCurrentPrefix", rule.preferCurrentPrefix)
                .put("expiresAt", rule.expiresAt ?: JSONObject.NULL).put("leaseSeconds", rule.leaseSeconds)
                .put("maxConnections", rule.maxConnections).put("idleTimeoutSec", rule.idleTimeoutSec)
                .put("desiredState", rule.desiredState).put("actualState", rule.actualState)
                .put("syncState", rule.syncState).put("firewallState", rule.firewallState)
                .put("revision", rule.revision).put("runtime", runtime)
            val body = JSONObject()
                .put("ok", true)
                .put("rules", org.json.JSONArray().put(row))
                .put("agentOnline", true).put("router", "BE72")
                .put("portRange", JSONObject().put("min", 1).put("max", 65535))
                .put("rulesLoaded", true).put("rulesRevision", 1L).put("revision", 1L)
                .toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val hub = "http://127.0.0.1:${server.address.port}"
        RouterWorkspaceStore.resetActive()
        RouterWorkspaceStore.bindHubRoot(context, workspaceId, hub)
        RouterWorkspaceStore.activate(context, hub, workspaceId)
        context.getSharedPreferences(routerWorkspacePreferencesName(workspaceId, hub), Context.MODE_PRIVATE)
            .edit().putString("token", "mapping-ui-token").commit()
        val prefs = AppPrefs(context, workspaceId)
        val cache = PortMappingMemoryCache.forScope(prefs.hub, prefs.token)
        cache.rules = listOf(rule)
        cache.rulesRevision = 1L
        cache.snapshotRevision = 1L
        cache.agent = PortMapAgentInfo(true, "BE72", "", 1, 65535)
        cache.devices = devices
        if (seedNativeAndUpnp) {
            val slow = RouterSlowDataCache.forScope(prefs.hub, prefs.token)
            slow.portMappings = listOf(
                NativePortMapRule(
                    ruleName = "NAS SSH", srcPort = "2222", destIp = "192.168.1.50",
                    destPort = "54133", proto = "tcp",
                )
            )
            slow.portMappingsLoaded = true
            slow.portMappingsAt = System.currentTimeMillis()
            slow.upnpState = UpnpState(
                enabled = true,
                wan = "AUTO",
                mappings = listOf(
                    UpnpMapping("Plex", "192.168.1.50", "TCP", "32400", "32400"),
                    UpnpMapping("NAT-PMP Mapping", "192.168.1.120", "UDP", "5351", "49152"),
                ),
            )
            slow.upnpAt = System.currentTimeMillis()
        }
        compose.setContent {
            MaterialTheme { MappingAndUpnpScreen(prefs, onBack = {}) }
        }
    }

    private fun waitingRule(longName: String, longIpv6: String): PortMapRule = PortMapRule(
        id = "map-long", name = longName, enabled = true, mode = "6to6", listenPort = 9443,
        targetType = "device", targetMode = "ipv6_full", targetIpv4 = "",
        targetIpv6 = longIpv6, targetIpv6Snapshot = longIpv6, targetIpv6Suffix = "",
        targetMac = "aa:bb:cc:dd:ee:01", targetPort = 443, serviceType = "", transportProtocol = "TCP",
        preferCurrentPrefix = true, expiresAt = null, leaseSeconds = 0L, maxConnections = 32, idleTimeoutSec = 300,
        desiredState = "running", actualState = "waiting_agent", syncState = "synced",
        runtime = PortMapRuntime(
            state = "waiting_agent",
            availableMetrics = PortMapRuntimeMetric.entries.toSet(),
        ),
    )

    private fun assertLongLayoutWithinScreen(longName: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(longName).assertIsDisplayed()
        compose.onNodeWithText("等待 Agent").assertIsDisplayed()
        compose.onNodeWithText("6→6").assertIsDisplayed()
        listOf("连接 0", "累计上传 0B", "累计下载 0B", "上行包 0", "下行包 0").forEach { text ->
            compose.onNodeWithText(text).assertIsDisplayed()
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$text exceeds card width", bounds.right <= root.right + 1 && bounds.left >= root.left - 1)
            assertTrue("$text wrapped", bounds.height <= with(compose.density) { 20.dp.roundToPx() })
        }
        val nameBounds = compose.onNodeWithText(longName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("long name exceeds card width", nameBounds.right <= root.right + 1 && nameBounds.left >= root.left - 1)

        val statusHeight = compose.onNodeWithText("等待 Agent", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "waiting status wrapped: height=$statusHeight limit=${with(compose.density) { 20.dp.roundToPx() }}",
            statusHeight <= with(compose.density) { 20.dp.roundToPx() },
        )
        compose.onAllNodesWithText(":9443", useUnmergedTree = true).fetchSemanticsNodes().forEach {
            assertTrue(it.boundsInRoot.right <= root.right + 1)
        }
    }

    @Test
    fun threeTabsRenderRealStatesAndZeroStatsAt360() {
        val now = System.currentTimeMillis() / 1000L
        val rule = PortMapRule(
            id = "map-1", name = "NAS SSH", enabled = true, mode = "6to4", listenPort = 9443,
            targetType = "manual", targetMode = "ipv4", targetIpv4 = "192.168.1.50",
            targetIpv6 = "", targetIpv6Suffix = "", targetMac = "aa:bb:cc:dd:ee:01", targetPort = 54133,
            serviceType = "", transportProtocol = "TCP", preferCurrentPrefix = true,
            expiresAt = now + 3600L, leaseSeconds = 3600L, maxConnections = 32, idleTimeoutSec = 300,
            desiredState = "running", actualState = "running", syncState = "synced",
            runtime = PortMapRuntime(
                state = "running", startedAt = now - 3600L, lastResolvedAt = now,
                availableMetrics = PortMapRuntimeMetric.entries.toSet(),
            ),
        )
        render("mapping-upnp-ui", rule, listOf(device("NAS", "aa:bb:cc:dd:ee:01", "192.168.1.50")), true)

        compose.onNodeWithText("IPv6映射").assertIsDisplayed()
        compose.onNodeWithText("NAS").assertIsDisplayed()
        compose.onNodeWithText("连接 0").assertIsDisplayed()
        compose.onNodeWithText("累计上传 0B").assertIsDisplayed()
        compose.onNodeWithText("累计下载 0B").assertIsDisplayed()
        compose.onNodeWithText("上行包 0").assertIsDisplayed()
        compose.onNodeWithText("下行包 0").assertIsDisplayed()
        compose.onNodeWithText("已运行", substring = true).assertIsDisplayed()
        compose.onNodeWithText("剩余", substring = true).assertIsDisplayed()

        compose.onNodeWithText("端口映射").performClick()
        compose.onNodeWithText("NAS SSH").assertIsDisplayed()
        compose.onNodeWithText("条规则").assertIsDisplayed()

        compose.onNodeWithText("UPnP").performClick()
        compose.onNodeWithText("UPnP 服务").assertIsDisplayed()
        compose.onNodeWithText("Plex").assertIsDisplayed()
        compose.onNodeWithText("NAT-PMP Mapping").assertIsDisplayed()
        compose.onAllNodesWithText("已映射").assertCountEquals(3)
        compose.onNodeWithContentDescription("新建端口映射").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("新建IPv6映射").assertIsNotDisplayed()
    }

    private fun renderRuleCard(
        width: Int,
        rule: PortMapRule,
        devices: List<DeviceItem>,
        favoriteHints: List<FavoriteServiceHint> = emptyList(),
    ) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width.dp)) {
                    PortMapRuleCard(
                        rule = rule,
                        devices = devices,
                        favoriteHints = favoriteHints,
                        quickEndpoint = "",
                        onOpenSsh = { _, _ -> },
                        onOpenWireGuard = {},
                        onOpen = {},
                        onEdit = {},
                        onToggle = {},
                        onDelete = {},
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun longIpv6AndWaitingAgentFitAt360() {
        val name = "绿联 NAS 媒体服务器与家庭数据备份中心长期运行节点"
        val ipv6 = "2409:8a50:2e41:b700:1111:2222:3333:4444"
        renderRuleCard(336, waitingRule(name, ipv6), listOf(device(name, "aa:bb:cc:dd:ee:01", "192.168.1.50")))
        assertLongLayoutWithinScreen(name)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun missingMetricsStayPlaceholdersWithoutClickingAt360() {
        val name = "长名称 NAS 未获得运行时统计"
        val rule = waitingRule(name, "2409:8a50:2e41:b700:1111:2222:3333:4444")
            .copy(runtime = PortMapRuntime(state = "waiting_agent"))
        renderRuleCard(336, rule, emptyList())
        listOf("连接 —", "累计上传 —", "累计下载 —", "上行包 —", "下行包 —").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun ipv6FavoriteEndpointBeatsDeviceBrandFallback() {
        val ipv6 = "2409:8a50:2e41:b700::3333:4444"
        val rule = waitingRule("家庭数据中心", "2409:8a50:02e41:b700:0000:0000:3333:4444").copy(targetPort = 32400)
        val favorite = FavoriteShortcut(
            id = "plex", title = "家庭影院", description = "", iconType = "builtin", iconValue = "media",
            lanUrl = "http://[$ipv6]:32400", wanUrl = "", order = 0, serviceType = "Plex", protocol = "TCP",
        )
        renderRuleCard(
            336, rule, listOf(device("绿联 NAS", "aa:bb:cc:dd:ee:01", "192.168.1.50")),
            favoriteServiceHints(listOf(favorite)),
        )
        compose.onNodeWithText("Plex").assertIsDisplayed()
    }

    @Test
    fun favoriteEditorPreservesUnknownProtocolWhenServiceTypeChanges() {
        var saved: FavoriteShortcut? = null
        val existing = FavoriteShortcut(
            id = "favorite-protocol", title = "VPN", description = "", iconType = "builtin", iconValue = "udp",
            lanUrl = "192.168.1.50:51820", wanUrl = "", order = 0, serviceType = "WireGuard", protocol = "UDP",
        )
        compose.setContent {
            MaterialTheme {
                FavoriteEditorSheet(existing = existing, ddnsRecords = emptyList(), onDismiss = {}, onSave = { saved = it })
            }
        }
        compose.onNodeWithText("WireGuard").performTextReplacement("NAS")
        compose.onNodeWithText("保存").performClick()
        assertEquals("UDP", saved?.protocol)
    }

    @Test
    fun selectingSourceKeepsItsTransportProtocol() {
        var saved: FavoriteShortcut? = null
        val option = FavoriteAddressOption(
            binding = FavoriteEndpointBinding("mapping", "map-udp-ssh", "lan"),
            group = "IPv6 端口映射", name = "UDP SSH", endpoint = "192.168.1.50:22",
            serviceType = "SSH", protocol = "UDP",
        )
        compose.setContent {
            MaterialTheme {
                FavoriteEditorSheet(
                    existing = null, ddnsRecords = emptyList(), addressOptions = listOf(option),
                    onDismiss = {}, onSave = { saved = it },
                )
            }
        }
        compose.onNodeWithContentDescription("选择内网地址来源").performClick()
        compose.onNodeWithText("IPv6 端口映射").performClick()
        compose.onNodeWithText("UDP SSH - 192.168.1.50:22").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals("UDP", saved?.protocol)
        assertEquals("SSH", saved?.serviceType)
    }

    @Test
    fun mappingFavoriteProtocolIsPersistedAndSynchronized() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs(context, "mapping-favorite-protocol")
        val rule = PortMapRule(
            id = "map-protocol", name = "Protocol test", enabled = true, mode = "6to4", listenPort = 9443,
            targetType = "manual", targetMode = "ipv4", targetIpv4 = "192.168.1.50",
            targetIpv6 = "", targetIpv6Suffix = "", targetMac = "", targetPort = 22,
            serviceType = "SSH", transportProtocol = "TCP", preferCurrentPrefix = true,
            expiresAt = null, leaseSeconds = 0L, maxConnections = 32, idleTimeoutSec = 300,
        )
        prefs.saveFavoriteShortcuts(listOf(favoriteFromPortMapRule(rule)))
        assertEquals("TCP", favoriteAddressOptions(prefs, listOf(rule), emptyList()).single { it.binding.side == "lan" }.protocol)
        val udpRule = rule.copy(transportProtocol = "UDP")
        assertEquals("UDP", favoriteAddressOptions(prefs, listOf(udpRule), emptyList()).single { it.binding.side == "lan" }.protocol)
        assertEquals("UDP", upsertMappingFavorite(prefs, udpRule).protocol)
        assertEquals("UDP", prefs.favoriteShortcuts().single().protocol)
        assertTrue(syncExistingMappingFavorite(prefs, rule))
        assertEquals("TCP", prefs.favoriteShortcuts().single().protocol)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun longIpv6AndWaitingAgentFitAt393() {
        val name = "绿联 NAS 媒体服务器与家庭数据备份中心长期运行节点"
        val ipv6 = "2409:8a50:2e41:b700:1111:2222:3333:4444"
        renderRuleCard(369, waitingRule(name, ipv6), listOf(device(name, "aa:bb:cc:dd:ee:01", "192.168.1.50")))
        assertLongLayoutWithinScreen(name)
    }
}
