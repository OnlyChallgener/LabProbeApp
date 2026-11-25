package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterWanStatusTest {
    @Test
    fun parsesWanAndWan1WithoutRenamingThem() {
        val root = JSONObject().put(
            "wans",
            JSONArray()
                .put(
                    JSONObject()
                        .put("wanId", "wan")
                        .put("configured", true)
                        .put("enabled", true)
                        .put("online", true)
                        .put("ipv4", "36.157.252.37")
                        .put("ipv6", "2409:8a50::1")
                        .put("physicalPortId", 5)
                        .put("physicalPortName", "WAN")
                        .put("linkSpeedMbps", 2500),
                )
                .put(
                    JSONObject()
                        .put("wanId", "wan1")
                        .put("configured", true)
                        .put("enabled", true)
                        .put("online", false)
                        .put("ipv4", "198.51.100.20")
                        .put("physicalPortId", 6)
                        .put("physicalPortName", "LAN2/WAN1")
                        .put("linkSpeedMbps", 2500),
                ),
        )
        val wans = parseRouterWans(root)
        assertEquals(listOf("WAN", "WAN1"), wans.map { it.displayName })
        assertTrue(wans[0].online)
        assertFalse(wans[1].online)
        assertEquals(2500, wans[1].linkSpeedMbps)
    }

    @Test
    fun unconfiguredSupportedWan1IsNotPresentedAsConfigured() {
        val root = JSONObject().put(
            "wans",
            JSONArray()
                .put(JSONObject().put("wanId", "wan").put("configured", true))
                .put(JSONObject().put("wanId", "wan1").put("supported", true).put("configured", false)),
        )
        val configured = parseRouterWans(root).filter { it.configured }
        assertEquals(listOf("WAN"), configured.map { it.displayName })
    }

    @Test
    fun physicalPortsKeepRoleAndNegotiatedSpeedSeparate() {
        val details = JSONObject().put(
            "ports",
            JSONArray()
                .put(
                    JSONObject()
                        .put("portId", 5)
                        .put("name", "WAN")
                        .put("panel_name", "WAN")
                        .put("status", "on")
                        .put("speed", "2500")
                        .put("role", "balance")
                        .put("port_type", "NORMAL"),
                )
                .put(
                    JSONObject()
                        .put("portId", 6)
                        .put("name", "LAN")
                        .put("panel_name", "LAN2/WAN1")
                        .put("status", "on")
                        .put("speed", "2500"),
                ),
        )
        val ports = parseRouterPorts(details)
        assertEquals(5, ports[0].portId)
        assertEquals("wan", ports[0].wanId)
        assertEquals("balance", ports[0].role)
        assertEquals("2.5G", ports[0].speedLabel)
        assertTrue(ports[1].isHybrid)
    }
}
