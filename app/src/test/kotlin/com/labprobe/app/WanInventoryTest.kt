package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WanInventoryTest {
    @Test
    fun parsesSupportedConfiguredOnlineAndCapabilitiesSeparately() {
        val payload = JSONObject("""
            {"wans":[
              {"wanId":"wan","logicalInterface":"wan","displayName":"WAN","supported":true,
               "configured":true,"enabled":true,"online":true,"defaultEgress":true,
               "families":["ipv4","ipv6"],"proto":"pppoe","ipv4":"36.157.252.37",
               "ipv6":"2409:8a50:2e04::1","linkSpeedMbps":2500,
               "capabilities":{"portMapping":true,"nativeDdns":true,"upnp":true,"speedtest":true,"natDiagnostic":true}},
              {"wanId":"wan1","logicalInterface":"wan1","displayName":"LAN2/WAN1","supported":true,
               "configured":false,"enabled":false,"online":false,"defaultEgress":false,
               "capabilities":{"portMapping":true,"nativeDdns":false,"upnp":false,"speedtest":false,"natDiagnostic":false}}
            ],"supported":true,"configuredCount":1,"onlineCount":1,"multiWanMode":"weight","updatedAt":1795259700}
        """.trimIndent())
        val inventory = parseWanInventory(payload)
        assertFalse(inventory.multiWanConfigured)
        assertEquals(1, inventory.configuredCount)
        assertEquals(1, inventory.onlineCount)
        assertEquals("weight", inventory.multiWanMode)
        assertTrue(inventory.selectable("speedtest").single().wanId == "wan")
        assertTrue(inventory.find("wan1")!!.supported)
        assertFalse(inventory.find("wan1")!!.selectable)
        assertEquals(2500, inventory.find("wan")!!.linkSpeedMbps)
    }

    @Test
    fun dualConfiguredWansExposeIndependentSelectableLines() {
        val payload = JSONObject("""
            {"wans":[
              {"wanId":"wan","supported":true,"configured":true,"enabled":true,"online":false,"capabilities":{"speedtest":true}},
              {"wanId":"wan1","supported":true,"configured":true,"enabled":true,"online":true,"capabilities":{"speedtest":true}}
            ],"configuredCount":2,"onlineCount":1}
        """.trimIndent())
        val inventory = parseWanInventory(payload)
        assertTrue(inventory.multiWanConfigured)
        assertEquals(listOf("wan", "wan1"), inventory.selectable("speedtest").map { it.wanId })
        assertFalse(inventory.find("wan")!!.online)
        assertTrue(inventory.find("wan1")!!.online)
    }

    @Test
    fun missingOptionalFieldsDoNotCreateWanEntries() {
        val inventory = parseWanInventory(JSONObject())
        assertTrue(inventory.wans.isEmpty())
        assertFalse(inventory.supported)
    }
}
