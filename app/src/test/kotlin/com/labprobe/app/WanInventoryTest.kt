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
        assertFalse(inventory.multiWanOnline)
        assertEquals(1, inventory.configuredCount)
        assertEquals(1, inventory.onlineCount)
        assertEquals("weight", inventory.multiWanMode)
        assertTrue(inventory.find("wan")!!.configurable)
        assertTrue(inventory.find("wan")!!.selectable)
        assertTrue(inventory.selectable("speedtest").single().wanId == "wan")
        assertTrue(inventory.find("wan1")!!.supported)
        assertFalse(inventory.find("wan1")!!.configurable)
        assertFalse(inventory.find("wan1")!!.selectable)
        assertEquals(2500, inventory.find("wan")!!.linkSpeedMbps)
    }

    @Test
    fun configuredOfflineWanIsNotSelectable() {
        val payload = JSONObject("""
            {"wans":[
              {"wanId":"wan","supported":true,"configured":true,"enabled":true,"online":false,"capabilities":{"speedtest":true}},
              {"wanId":"wan1","supported":true,"configured":true,"enabled":true,"online":true,"capabilities":{"speedtest":true}}
            ],"configuredCount":2,"onlineCount":1}
        """.trimIndent())
        val inventory = parseWanInventory(payload)
        assertTrue(inventory.multiWanConfigured)
        assertFalse(inventory.multiWanOnline)
        assertEquals(listOf("wan1"), inventory.selectable("speedtest").map { it.wanId })
        assertEquals("wan1", inventory.preferred("speedtest")?.wanId)
        assertFalse(inventory.find("wan")!!.online)
        assertTrue(inventory.find("wan")!!.configurable)
        assertFalse(inventory.find("wan")!!.selectable)
    }

    @Test
    fun dualOnlineWansExposeBothSelectableLines() {
        val payload = JSONObject("""
            {"wans":[
              {"wanId":"wan","supported":true,"configured":true,"enabled":true,"online":true,"capabilities":{"speedtest":true}},
              {"wanId":"wan1","supported":true,"configured":true,"enabled":true,"online":true,"capabilities":{"speedtest":true}}
            ],"configuredCount":2,"onlineCount":2}
        """.trimIndent())
        val inventory = parseWanInventory(payload)
        assertTrue(inventory.multiWanOnline)
        assertEquals(listOf("wan", "wan1"), inventory.selectable("speedtest").map { it.wanId })
    }

    @Test
    fun preferredAlwaysChoosesWanThenWan1RegardlessOfDefaultEgress() {
        fun wan(id: String, online: Boolean = true, defaultEgress: Boolean = false, speedtest: Boolean = true) = WanInfo(
            wanId = id,
            supported = true,
            configured = true,
            enabled = true,
            online = online,
            defaultEgress = defaultEgress,
            capabilities = mapOf("speedtest" to speedtest),
        )
        val inventory = WanInventory(
            wans = listOf(
                wan("wan2", defaultEgress = true),
                wan("wan1", defaultEgress = true),
                wan("wan"),
            ),
            configuredCount = 3,
            onlineCount = 3,
        )

        assertEquals(listOf("wan", "wan1", "wan2"), inventory.selectable("speedtest").map { it.wanId })
        assertEquals("wan", inventory.preferred("speedtest")?.wanId)
        assertEquals("wan1", inventory.copy(wans = inventory.wans.map { if (it.wanId == "wan") it.copy(online = false) else it }).preferred("speedtest")?.wanId)
        assertEquals("wan2", inventory.copy(wans = inventory.wans.map {
            if (it.wanId == "wan") it.copy(online = false) else if (it.wanId == "wan1") it.copy(capabilities = mapOf("speedtest" to false)) else it
        }).preferred("speedtest")?.wanId)
    }

    @Test
    fun configuredOrderKeepsWanFirstWithoutRequiringOnline() {
        val inventory = WanInventory(wans = listOf(
            WanInfo(wanId = "wan2", supported = true, configured = true, enabled = true, online = false),
            WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false),
            WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = false),
        ))

        assertEquals(listOf("wan", "wan1", "wan2"), inventory.configured().map { it.wanId })
        assertTrue(inventory.online().isEmpty())
    }

    @Test
    fun wan1OnlyOnlineInventoryHasNoPrimaryWanOption() {
        val payload = JSONObject("""
            {"wans":[
              {"wanId":"wan","supported":true,"configured":true,"enabled":true,"online":false,"capabilities":{"speedtest":true}},
              {"wanId":"wan1","supported":true,"configured":true,"enabled":true,"online":true,"capabilities":{"speedtest":true}}
            ],"configuredCount":2,"onlineCount":1}
        """.trimIndent())
        val inventory = parseWanInventory(payload)
        assertFalse(inventory.multiWanOnline)
        assertEquals(listOf("wan1"), inventory.selectable("speedtest").map { it.wanId })
    }

    @Test
    fun missingSupportFieldsAreCompatibleButExplicitFalseIsNot() {
        val inventory = parseWanInventory(JSONObject("""
            {"wans":[
              {"wanId":"legacy","configured":true,"enabled":true,"online":true},
              {"wanId":"blocked","supported":false,"configured":true,"enabled":true,"online":true},
              {"wanId":"zero","supported":true,"configured":true,"enabled":true,"online":true,"capabilities":{"speedtest":false}}
            ],"onlineCount":3}
        """.trimIndent()))

        assertTrue(inventory.find("legacy")!!.supported)
        assertTrue(inventory.find("legacy")!!.supports("speedtest"))
        assertEquals(listOf("legacy"), inventory.selectable("speedtest").map { it.wanId })
        assertFalse(inventory.find("blocked")!!.configurable)
        assertFalse(inventory.find("zero")!!.supports("speedtest"))
    }

    @Test
    fun lineCapabilitySupportDoesNotRequireOnlineState() {
        val inventory = WanInventory(wans = listOf(
            WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = false),
            WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false),
        ))
        assertTrue(inventory.supportsLineCapability("portMapping"))
        assertTrue(inventory.selectable("portMapping").isEmpty())
        assertFalse(inventory.copy(wans = inventory.wans.map { it.copy(capabilities = mapOf("portMapping" to false)) }).supportsLineCapability("portMapping"))
    }

    @Test
    fun missingOptionalFieldsDoNotCreateWanEntries() {
        val inventory = parseWanInventory(JSONObject())
        assertTrue(inventory.wans.isEmpty())
        assertFalse(inventory.supported)
    }
}
