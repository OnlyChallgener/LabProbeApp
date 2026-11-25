package com.labprobe.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class HomeWanDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun legacyWan6RowsDoNotInventPrimaryOrBackupRoles() {
        val router = JSONObject("""{"wan6List":[{"ip":"2001:db8::1"},{"ip":"2001:db8::2"}]}""")
        val rows = routerWan6Rows(router)
        assertEquals(listOf("路由 WAN6", "路由 WAN6 2"), rows.map { it.first })
        assertTrue(rows.none { it.first.contains("主用") || it.first.contains("备用") })
    }

    @Test fun sameNameStunRulesWithDifferentIdsRemainDistinct() {
        val rules = listOf(
            StunRule("a", "HTTPS 穿透", true, 30001, targetIpv4 = "192.168.5.10", targetPort = 443,
                serviceType = "HTTPS", transportProtocol = "TCP", forwardMode = "router_native",
                actualState = "mapped", firewallState = "ready", nativeMappingState = "ready",
                runtime = StunRuntime(publicEndpoint = "198.51.100.1:30001"), wanId = "wan"),
            StunRule("b", "HTTPS 穿透", true, 30002, targetIpv4 = "192.168.5.11", targetPort = 443,
                serviceType = "HTTPS", transportProtocol = "TCP", forwardMode = "router_native",
                actualState = "mapped", firewallState = "ready", nativeMappingState = "ready",
                runtime = StunRuntime(publicEndpoint = "198.51.100.2:30002"), wanId = "wan1"),
        )
        val rows = buildStunRowsForHome(null, rules)
        assertEquals(2, rows.size)
        assertEquals(setOf("a", "b"), rows.map { it.id }.toSet())
        assertEquals(setOf("wan", "wan1"), rows.map { it.wanId }.toSet())
    }

    @Test fun sameEndpointDoesNotCollapseDifferentRuleIdentity() {
        val rules = listOf(
            StunRule("a", "SSH", true, 30001, targetIpv4 = "192.168.5.10", targetPort = 22,
                serviceType = "SSH", transportProtocol = "TCP", forwardMode = "router_native",
                actualState = "mapped", firewallState = "ready", nativeMappingState = "ready",
                runtime = StunRuntime(publicEndpoint = "198.51.100.1:30001")),
            StunRule("b", "SSH", true, 30001, targetIpv4 = "192.168.5.11", targetPort = 22,
                serviceType = "SSH", transportProtocol = "TCP", forwardMode = "router_native",
                actualState = "mapped", firewallState = "ready", nativeMappingState = "ready",
                runtime = StunRuntime(publicEndpoint = "198.51.100.1:30001")),
        )
        assertEquals(2, buildStunRowsForHome(null, rules).size)
    }

    @Test fun authoritativeApiListDoesNotGetOverwrittenByStatusFallback() {
        val status = JSONObject("""{"stunRules":[{"id":"a","name":"SSH","enabled":true,
            "runtime":{"publicEndpoint":"203.0.113.99:1"},"targetPort":22}]}""")
        val api = listOf(StunRule("a", "SSH", true, 30001, targetIpv4 = "192.168.5.10", targetPort = 22,
            serviceType = "SSH", transportProtocol = "TCP", forwardMode = "router_native",
            actualState = "mapped", firewallState = "ready", nativeMappingState = "ready",
            runtime = StunRuntime(publicEndpoint = "198.51.100.1:30001")))
        val rows = buildStunRowsForHome(status, api)
        assertEquals(1, rows.size)
        assertEquals("198.51.100.1:30001", rows.single().endpoint)
        assertTrue(buildStunRowsForHome(status, emptyList()).isEmpty())
    }

    @Test fun blankRuntimeEndpointFallsBackToPublicHostAndPort() {
        val status = JSONObject("""{"stunRules":[{"id":"a","name":"SSH","enabled":true,
            "runtime":{"publicEndpoint":""},"addresses":{"public":{"host":"198.51.100.1","port":2222}},"targetPort":22}]}""")
        assertEquals("198.51.100.1:2222", buildStunRowsForHome(status, null).single().endpoint)
    }

    @Test fun stunCacheRoundTripPreservesWanAndRuleIdentity() {
        val rows = listOf(HomeStunRow("SSH", "198.51.100.1:22", "SSH", "a", "api", "wan1", "192.168.5.1:22", true))
        val decoded = decodeHomeStunRows(encodeHomeStunRows(rows))
        assertEquals(rows, decoded)
    }

    @Test fun vpnAndStunMergeOnlyDuplicatesTheSameServiceEndpoint() {
        val stun = listOf(HomeStunRow("WireGuard", "198.51.100.1:51820", "WIREGUARD", id = "a"))
        val vpn = listOf("WireGuard" to "198.51.100.1:51820", "SSH" to "198.51.100.1:51820")
        val rows = buildHomeVpnStunRows(stun, vpn)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.serviceType == "SSH" })
    }

    @Test fun wanHealthDistinguishesPartialAndTotalOutage() {
        fun wan(id: String, online: Boolean, default: Boolean = false) = RouterWanUi(
            id, id.uppercase(), true, true, online, default, "DHCP", if (online) "192.0.2.1" else "",
            "", "", "", emptyList(), 0, 0, "", 0,
        )
        assertEquals("", wanHealthStatusLabel(listOf(wan("wan", true))))
        assertEquals("全部正常", wanHealthStatusLabel(listOf(wan("wan", true, true), wan("wan1", true))))
        assertEquals("部分线路异常", wanHealthStatusLabel(listOf(wan("wan", true, true), wan("wan1", false))))
        assertEquals("全部线路异常", wanHealthStatusLabel(listOf(wan("wan", false, true), wan("wan1", false))))
    }

    @Test fun legacyWanRowsWithoutBooleansRemainVisible() {
        val row = parseRouterWans(JSONObject("""{"wans":[{"wanId":"wan1","proto":"dhcp","ipv4":"192.0.2.2"}]}""")).single()
        assertTrue(row.configured)
        assertTrue(row.enabled)
        assertTrue(row.online)
    }

    @Test fun privacyDisplayKeepsRawAddressForCopy() {
        val raw = RouterWanUi("wan", "WAN", true, true, true, true, "DHCP", "198.51.100.1", "2001:db8::1",
            "198.51.100.254", "", listOf("1.1.1.1"), 0, 5, "WAN", 2500)
        val masked = raw.copy(ipv4 = "198.51.***.***", ipv6 = "****:****::****", gateway = "198.51.***.***")
        val items = routerWanInfoItems(masked, raw).associateBy { it.label }
        assertEquals("198.51.***.***", items.getValue("IPv4").value)
        assertEquals("198.51.100.1", items.getValue("IPv4").copyValue)
        assertEquals("2001:db8::1", items.getValue("IPv6").copyValue)
    }

    @Test fun multiWanExitCardShowsBothLinesAndOfflineWan1() {
        val wans = listOf(
            RouterWanUi("wan", "WAN", true, true, true, true, "PPPoE", "198.51.100.1", "2001:db8::1",
                "", "", emptyList(), 0, 5, "WAN", 2500, publicIpv4 = "203.0.113.1"),
            RouterWanUi("wan1", "WAN1", true, true, false, false, "DHCP", "", "",
                "", "", emptyList(), 0, 6, "LAN2/WAN1", 2500),
        )
        compose.setContent { MaterialTheme { HealthExitCard(null, null, false, wans = wans) } }
        compose.onNodeWithText("WAN 接口 IPv4").assertIsDisplayed()
        compose.onNodeWithText("WAN 公网 IPv4").assertIsDisplayed()
        compose.onNodeWithText("WAN1 接口 IPv6").assertDoesNotExist()
        compose.onNodeWithText("WAN1").assertIsDisplayed()
        compose.onNodeWithText("离线").assertIsDisplayed()
    }
}
