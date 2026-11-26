package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NatWanOptionsTest {
    @Test
    fun singleOnlineWanDoesNotOfferOfflineWan1() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", displayName = "WAN", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("natDiagnostic" to true)),
                WanInfo(wanId = "wan1", displayName = "WAN1", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("natDiagnostic" to true)),
            ),
            configuredCount = 2,
            onlineCount = 1,
        )
        assertEquals(listOf("wan" to "WAN"), natWanOptions(inventory, "wan1"))
    }

    @Test
    fun dualOnlineWansOfferBothLines() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", displayName = "WAN", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("natDiagnostic" to true)),
                WanInfo(wanId = "wan1", displayName = "WAN1", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("natDiagnostic" to true)),
            ),
            configuredCount = 2,
            onlineCount = 2,
        )
        assertEquals(listOf("wan" to "WAN", "wan1" to "WAN1"), natWanOptions(inventory, "wan"))
    }

    @Test
    fun wan1OnlyOnlineDoesNotOfferPrimaryWan() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", displayName = "WAN", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("natDiagnostic" to true)),
                WanInfo(wanId = "wan1", displayName = "WAN1", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("natDiagnostic" to true)),
            ),
            configuredCount = 2,
            onlineCount = 1,
        )
        assertEquals(listOf("wan1" to "WAN1"), natWanOptions(inventory, "wan"))
    }
}
