package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FirewallInterfaceTest {
    @Test
    fun parserKeepsFirmwareInterfaceListIncludingVlan() {
        val state = parseFirewall(JSONObject("""{
            "list":[],"maxLen":20,
            "interfaces":["wan","wan1","lan","lan_234","lan_235"]
        }"""))

        assertEquals(listOf("wan", "wan1", "lan", "lan_234", "lan_235"), state.interfaces)
    }

    @Test
    fun legacyFallbackKeepsCurrentOfflineInterface() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", logicalInterface = "wan", configured = true, online = true),
                WanInfo(wanId = "wan1", logicalInterface = "wan1", configured = true, online = false),
            ),
            configuredCount = 2,
        )

        assertEquals(listOf("wan", "wan1"), firewallWanOptions(inventory, "wan1"))
    }
}
