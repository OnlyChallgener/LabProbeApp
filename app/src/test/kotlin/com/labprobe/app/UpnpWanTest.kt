package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpWanTest {
    @Test
    fun singleOnlineWanDoesNotExposeWan1ButtonOrSelector() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(
                    wanId = "wan", supported = true, configured = true, enabled = true, online = true,
                    capabilities = mapOf("upnp" to true),
                ),
                WanInfo(
                    wanId = "wan1", supported = true, configured = true, enabled = true, online = false,
                    capabilities = mapOf("upnp" to true),
                ),
            ),
            configuredCount = 2,
            onlineCount = 1,
        )
        assertEquals(listOf("AUTO", "WAN"), upnpWanOptions(inventory, "AUTO"))
        assertFalse(upnpShowsWanSelector(inventory))
    }

    @Test
    fun dualOnlineWansExposeBothButtonsAndSelector() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("upnp" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("upnp" to true)),
            ),
            configuredCount = 2,
            onlineCount = 2,
        )
        assertEquals(listOf("AUTO", "WAN", "WAN1"), upnpWanOptions(inventory, "AUTO"))
        assertTrue(upnpShowsWanSelector(inventory))
    }

    @Test
    fun offlineCurrentBindingIsPreservedWithoutShowingSelector() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("upnp" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("upnp" to true)),
            ),
            configuredCount = 2,
            onlineCount = 1,
        )
        assertEquals(listOf("AUTO", "WAN", "WAN1"), upnpWanOptions(inventory, "WAN1"))
        assertFalse(upnpShowsWanSelector(inventory))
    }

    @Test
    fun wan1OnlyOnlineDoesNotShowPrimaryWanOrSelector() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("upnp" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("upnp" to true)),
            ),
            configuredCount = 2,
            onlineCount = 1,
        )
        assertEquals(listOf("AUTO", "WAN1"), upnpWanOptions(inventory, "AUTO"))
        assertFalse(upnpShowsWanSelector(inventory))
    }

    @Test
    fun knownZeroCapabilityDoesNotInventWan() {
        val inventory = WanInventory(
            wans = listOf(WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("upnp" to false))),
            configuredCount = 1,
            onlineCount = 1,
        )
        assertEquals(emptyList<String>(), upnpWanOptions(inventory, ""))
    }

    @Test
    fun legacyHubRetainsAutoAndWanWithoutInventory() {
        assertEquals(listOf("AUTO", "WAN"), upnpWanOptions(null, "AUTO"))
        assertTrue(upnpShowsWanSelector(null))
    }

    @Test
    fun parserAcceptsWanIdFromNewHubWithoutLosingWanField() {
        val state = parseUpnp(JSONObject("""{"enable_upnp":"true","wanId":"wan1","upnpds":[]}"""))
        assertEquals("WAN1", state.wan)
    }
}
