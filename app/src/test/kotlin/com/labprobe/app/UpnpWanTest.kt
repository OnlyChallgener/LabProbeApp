package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UpnpWanTest {
    @Test
    fun singleWanDoesNotExposeWan1Button() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(
                    wanId = "wan", supported = true, configured = true, enabled = true,
                    capabilities = mapOf("upnp" to true),
                ),
                WanInfo(
                    wanId = "wan1", supported = true, configured = false, enabled = false,
                    capabilities = mapOf("upnp" to false),
                ),
            ),
            configuredCount = 1,
        )
        assertEquals(listOf("AUTO", "WAN"), upnpWanOptions(inventory, "AUTO"))
    }

    @Test
    fun configuredWan1AddsButtonEvenWhenOffline() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, capabilities = mapOf("upnp" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("upnp" to true)),
            ),
            configuredCount = 2,
        )
        assertEquals(listOf("AUTO", "WAN", "WAN1"), upnpWanOptions(inventory, "AUTO"))
    }

    @Test
    fun parserAcceptsWanIdFromNewHubWithoutLosingWanField() {
        val state = parseUpnp(JSONObject("""{"enable_upnp":"true","wanId":"wan1","upnpds":[]}"""))
        assertEquals("WAN1", state.wan)
    }
}
