package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePortMapWanTest {
    @Test
    fun emptySrcRemainsAllWansInModelAndWritePayload() {
        val rule = NativePortMapRule(ruleName = "all", src = "", srcPort = "80")
        assertEquals("all", rule.wanId)
        assertEquals("全部WAN", nativePortWanLabel(rule))
        val json = rule.toJson()
        assertEquals("all", json.getString("wanId"))
        assertEquals("", json.getString("src"))
    }

    @Test
    fun wan1BindingIsDisplayedAndSerialized() {
        val rule = NativePortMapRule(ruleName = "wan1", src = "wan1", srcPort = "81")
        assertEquals("wan1", rule.wanId)
        assertEquals("WAN1", nativePortWanLabel(rule))
        assertEquals("wan1", rule.toJson().getString("wanId"))
        assertEquals("wan1", rule.toJson().getString("src"))
    }

    @Test
    fun specifiedSourceIpDoesNotReplaceWanDestinationBinding() {
        val rule = NativePortMapRule(src = "wan1", srcIp = "203.0.113.10")
        val json = rule.toJson()
        assertEquals("wan1", json.getString("src"))
        assertEquals("203.0.113.10", json.getString("srcIp"))
    }

    @Test
    fun parserKeepsLegacyAndAllWanRules() {
        val rules = parseNativePortRules(JSONObject("""
            {"portMapping":[
              {"ruleName":"legacy","srcPort":"80"},
              {"ruleName":"all","src":"","srcPort":"81"},
              {"ruleName":"second","src":"wan1","srcPort":"82"}
            ]}
        """.trimIndent()))
        assertEquals(listOf("wan", "all", "wan1"), rules.map { it.wanId })
    }

    @Test
    fun singleOnlineWanHidesSelectorAndOfflineBindingOption() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("portMapping" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("portMapping" to true)),
            ),
            configuredCount = 2,
            onlineCount = 1,
        )
        assertEquals(listOf("all", "wan"), nativePortMapLineOptions(inventory))
        assertFalse(nativePortMapShowsLineSelector(inventory))
    }

    @Test
    fun dualOnlineWansShowSelectorWithBothLines() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("portMapping" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("portMapping" to true)),
            ),
            configuredCount = 2,
            onlineCount = 2,
        )
        assertEquals(listOf("all", "wan", "wan1"), nativePortMapLineOptions(inventory))
        assertTrue(nativePortMapShowsLineSelector(inventory))
    }

    @Test
    fun wan1OnlyOnlineHidesPrimaryWanAndSelector() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("portMapping" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = true, capabilities = mapOf("portMapping" to true)),
            ),
            configuredCount = 2,
            onlineCount = 1,
        )
        assertEquals(listOf("all", "wan1"), nativePortMapLineOptions(inventory))
        assertFalse(nativePortMapShowsLineSelector(inventory))
    }
}
