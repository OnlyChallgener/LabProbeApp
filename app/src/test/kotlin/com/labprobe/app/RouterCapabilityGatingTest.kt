package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterCapabilityGatingTest {
    private val zeroCapabilities = WanInventory(
        wans = listOf(
            WanInfo(
                wanId = "wan",
                logicalInterface = "eth0",
                supported = true,
                configured = true,
                enabled = true,
                capabilities = mapOf("portMapping" to false, "upnp" to false, "natDiagnostic" to false),
            ),
        ),
        supported = true,
        configuredCount = 1,
    )

    @Test
    fun knownZeroCapabilityHidesUnsupportedSuiteTabs() {
        assertEquals(listOf("ipv6"), mappingSuitePages(RouterCapabilities(nativePortMapping = true, upnp = true), zeroCapabilities))
    }

    @Test
    fun oldHubWithoutCapabilitiesKeepsLegacyTabs() {
        assertEquals(listOf("ipv6", "portMapping", "upnp"), mappingSuitePages(null, null))
    }

    @Test
    fun wan1OnlyInventoryExposesOnlyRealNativeCapabilities() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = false, enabled = false, capabilities = mapOf("portMapping" to true, "upnp" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("portMapping" to true, "upnp" to false)),
            ),
            supported = true,
            configuredCount = 1,
        )
        assertEquals(listOf("ipv6", "portMapping"), mappingSuitePages(null, inventory))
    }

    @Test
    fun routerToolEntriesFollowDeviceCapabilities() {
        val capabilities = RouterCapabilities(configured = true, firewall = true, nativePortMapping = false, upnp = false, diagnostic = false, wireguard = false)
        assertFalse(routerToolRouteVisible("tool_router_firewall", capabilities.copy(firewall = false), null))
        assertFalse(routerToolRouteVisible("tool_router_diag", capabilities, null))
        assertFalse(routerToolRouteVisible("tool_router_nat", capabilities, zeroCapabilities))
        assertFalse(routerToolRouteVisible("tool_wireguard", capabilities, null))
        assertFalse(routerToolRouteVisible("tool_portmap", capabilities, zeroCapabilities))
        assertTrue(routerToolRouteVisible("tool_router_ipv6", capabilities, null))
        assertTrue(routerToolRouteVisible("tool_router_ddns", capabilities, zeroCapabilities))
        assertTrue(routerToolRouteVisible("tool_router_firewall", null, null))
    }

    @Test
    fun firewallUsesLogicalInterfacesAndKeepsOfflineConfiguredWan1() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", logicalInterface = "eth0", supported = true, configured = true, enabled = true),
                WanInfo(wanId = "wan1", logicalInterface = "eth1", supported = true, configured = true, enabled = true, online = false),
            ),
            supported = true,
            configuredCount = 2,
        )
        assertEquals(listOf("eth0", "eth1"), firewallWanOptions(inventory, "eth0"))
        assertEquals(listOf("wan"), firewallWanOptions(null, "wan"))
        assertEquals(listOf("wan", "legacy0"), firewallWanOptions(null, "legacy0"))
    }
}
