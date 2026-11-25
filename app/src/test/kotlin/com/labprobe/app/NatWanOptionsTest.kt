package com.labprobe.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NatWanOptionsTest {
    @Test
    fun singleWanDoesNotOfferWan1() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", displayName = "WAN", supported = true, configured = true, enabled = true, capabilities = mapOf("natDiagnostic" to true)),
                WanInfo(wanId = "wan1", displayName = "WAN1", supported = true, configured = false, enabled = false, capabilities = mapOf("natDiagnostic" to false)),
            ),
            configuredCount = 1,
        )
        assertEquals(listOf("wan" to "WAN"), natWanOptions(inventory, "wan"))
    }

    @Test
    fun configuredOfflineWan1RemainsAvailable() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", displayName = "WAN", supported = true, configured = true, enabled = true, capabilities = mapOf("natDiagnostic" to true)),
                WanInfo(wanId = "wan1", displayName = "WAN1", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("natDiagnostic" to true)),
            ),
            configuredCount = 2,
        )
        assertEquals(listOf("wan" to "WAN", "wan1" to "WAN1"), natWanOptions(inventory, "wan"))
    }
}
