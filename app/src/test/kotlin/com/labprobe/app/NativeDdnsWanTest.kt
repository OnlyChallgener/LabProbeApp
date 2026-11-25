package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDdnsWanTest {
    @Test
    fun singleWanOnlyOffersWan() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, capabilities = mapOf("nativeDdns" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = false, enabled = false, capabilities = mapOf("nativeDdns" to false)),
            ),
            configuredCount = 1,
        )
        assertEquals(listOf("wan"), nativeDdnsWanOptions(inventory, "wan"))
    }

    @Test
    fun configuredOfflineWan1RemainsSelectable() {
        val inventory = WanInventory(
            wans = listOf(
                WanInfo(wanId = "wan", supported = true, configured = true, enabled = true, capabilities = mapOf("nativeDdns" to true)),
                WanInfo(wanId = "wan1", supported = true, configured = true, enabled = true, online = false, capabilities = mapOf("nativeDdns" to true)),
            ),
            configuredCount = 2,
        )
        assertEquals(listOf("wan", "wan1"), nativeDdnsWanOptions(inventory, "wan"))
    }

    @Test
    fun recordSerializesWanIdentityWithoutLosingInterface() {
        val record = DdnsRecord(domain = "a.example.com", interfaceName = "wan1")
        val json = record.toJson(null)
        assertEquals("wan1", record.wanId)
        assertEquals("wan1", json.getString("wanId"))
        assertEquals("wan1", json.getString("interface"))
    }

    @Test
    fun parserAcceptsWanIdWhenInterfaceAliasIsMissing() {
        val record = parseDdnsList(JSONObject("""
            {"list":[{"id":"a","domain":"a.example.com","wanId":"wan1"}]}
        """.trimIndent())).single()
        assertEquals("wan1", record.interfaceName)
    }
}
