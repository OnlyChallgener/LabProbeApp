package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
