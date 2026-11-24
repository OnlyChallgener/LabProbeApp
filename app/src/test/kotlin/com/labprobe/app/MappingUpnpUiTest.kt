package com.labprobe.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class MappingUpnpUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threeTabsRenderRealStatesAndIpv6TrafficExpands() {
        val context = RuntimeEnvironment.getApplication()
        val workspaceId = "mapping-upnp-ui"
        val hub = "http://127.0.0.1:1"
        RouterWorkspaceStore.resetActive()
        RouterWorkspaceStore.bindHubRoot(context, workspaceId, hub)
        RouterWorkspaceStore.activate(context, hub, workspaceId)
        context.getSharedPreferences(routerWorkspacePreferencesName(workspaceId, hub), Context.MODE_PRIVATE)
            .edit().putString("token", "mapping-ui-token").commit()
        val prefs = AppPrefs(context, workspaceId)
        val now = System.currentTimeMillis() / 1000L
        val rule = PortMapRule(
            id = "map-1",
            name = "NAS SSH",
            enabled = true,
            mode = "6to4",
            listenPort = 9443,
            targetType = "manual",
            targetMode = "ipv4",
            targetIpv4 = "192.168.1.50",
            targetIpv6 = "",
            targetIpv6Suffix = "",
            targetMac = "aa:bb:cc:dd:ee:01",
            targetPort = 54133,
            serviceType = "",
            transportProtocol = "TCP",
            preferCurrentPrefix = true,
            expiresAt = now + 3600L,
            leaseSeconds = 3600L,
            maxConnections = 32,
            idleTimeoutSec = 300,
            desiredState = "running",
            actualState = "running",
            syncState = "synced",
            runtime = PortMapRuntime(
                state = "running",
                activeConnections = 12L,
                totalUploadBytes = 1024L * 1024L,
                totalDownloadBytes = 2L * 1024L * 1024L,
                startedAt = now - 3600L,
                lastResolvedAt = now,
            ),
        )
        val cache = PortMappingMemoryCache.forScope(prefs.hub, prefs.token)
        cache.rules = listOf(rule)
        cache.rulesRevision = 1L
        cache.snapshotRevision = 1L
        cache.agent = PortMapAgentInfo(true, "BE72", "", 1, 65535)
        cache.devices = listOf(
            DeviceItem(
                name = "NAS", mac = "aa:bb:cc:dd:ee:01", online = true, ip = "192.168.1.50",
                ssid = "", band = "", rssi = "", rxrate = "", onlineSince = "", offlineAt = "",
                onlineDurationText = "", lastSeenAt = "", manufacture = "UGREEN", devType = "nas",
            )
        )
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

        compose.setContent {
            MaterialTheme { MappingAndUpnpScreen(prefs, onBack = {}) }
        }

        compose.onNodeWithText("IPv6映射").assertIsDisplayed()
        compose.onNodeWithText("NAS").assertIsDisplayed()
        compose.onNodeWithText("待同步").assertIsDisplayed()
        compose.onNodeWithText("NAS").performClick()
        compose.onNodeWithText("连接 12").assertIsDisplayed()
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
}
