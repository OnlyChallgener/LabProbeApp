package com.labprobe.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouterWanDetailsTest {
    @Test fun parserKeepsEveryFieldOnItsOwnWan() {
        val root = JSONObject("""{
          "telemetry":{"wans":[
            {"wanId":"wan","uploadBps":100,"totalUploadBytes":1000},
            {"wanId":"wan1","downloadBps":200,"totalDownloadBytes":2000}
          ]},
          "wans":[
            {"wanId":"wan","displayName":"WAN","configured":true,"enabled":true,"online":true,"proto":"pppoe",
             "ipv4":"198.51.100.1","gateway":"198.51.100.254","mtu":"1480","vlanId":"10","mac":"aa:bb:cc:dd:ee:01",
             "remark":"宽带 A","operator":"运营商 A","pppoeUsername":"user-a","physicalPortName":"WAN","linkSpeedMbps":2500},
            {"wanId":"wan1","displayName":"WAN1","configured":true,"enabled":true,"online":false,"proto":"dhcp",
             "ipv4":"","dhcpLease":"","physicalPortName":"LAN2/WAN1"}
          ]
        }""")
        val credentials = JSONObject("""{
          "username":"legacy-user","password":"legacy-password",
          "wans":[
            {"wanId":"wan","username":"user-a","password":"password-a","passwordConfigured":true},
            {"wanId":"wan1","username":"","password":"","passwordConfigured":false}
          ]
        }""")
        val wans = parseRouterWans(root, credentials).associateBy { it.wanId }
        val wan = wans.getValue("wan")
        val wan1 = wans.getValue("wan1")
        assertEquals("user-a", wan.pppoeUsername)
        assertEquals("password-a", wan.broadbandPassword)
        assertEquals("运营商 A", wan.operator)
        assertEquals("1480", wan.mtu)
        assertEquals(100L, wan.uploadBps)
        assertNull(wan.downloadBps)
        assertEquals("", wan1.operator)
        assertEquals("", wan1.mtu)
        assertEquals("", wan1.gateway)
        assertEquals("", wan1.pppoeUsername)
        assertEquals("", wan1.broadbandPassword)
        assertEquals(200L, wan1.downloadBps)
        assertNull(wan1.uploadBps)
        assertFalse(wan1.online)
    }

    @Test fun dynamicFieldsFollowConnectionType() {
        val base = RouterWanUi(
            wanId = "wan", displayName = "WAN", configured = true, enabled = true, online = true,
            defaultEgress = true, proto = "PPPoE", ipv4 = "198.51.100.1", ipv6 = "", gateway = "198.51.100.254",
            gateway6 = "", dns = listOf("1.1.1.1"), uptimeSeconds = 60, physicalPortId = 5,
            physicalPortName = "WAN", linkSpeedMbps = 2500,
        )
        val pppoe = routerWanInfoItems(base.copy(pppoeUsername = "user", broadbandPassword = "secret"))
        assertTrue(pppoe.any { it.label == "宽带账号" && it.value == "user" })
        assertTrue(pppoe.any { it.label == "宽带密码" && it.value == "••••••••" && it.copyValue == "secret" })

        val dhcp = routerWanInfoItems(base.copy(proto = "DHCP", pppoeUsername = "", broadbandPassword = "", dhcpLease = "2 小时"))
        assertFalse(dhcp.any { it.label == "宽带账号" || it.label == "宽带密码" })
        assertTrue(dhcp.any { it.label == "DHCP 租期" && it.value == "2 小时" })

        val bridge = routerWanInfoItems(base.copy(proto = "桥接", ipv4 = "", gateway = "", dns = emptyList(), pppoeUsername = "", broadbandPassword = ""))
        assertFalse(bridge.any { it.label in setOf("IPv4", "网关", "DNS", "宽带账号", "宽带密码") })
        assertTrue(bridge.any { it.label == "链路" || it.label == "物理端口" })

        val static = routerWanInfoItems(base.copy(proto = "静态 IP", netmask = "255.255.255.0", pppoeUsername = "", broadbandPassword = ""))
        assertTrue(static.any { it.label == "IPv4" })
        assertTrue(static.any { it.label == "子网掩码" })
        assertTrue(static.any { it.label == "网关" })
        assertFalse(static.any { it.label == "宽带账号" || it.label == "宽带密码" })
    }

    @Test fun missingTrafficSideIsNotRenderedAsZero() {
        val wan = RouterWanUi(
            wanId = "wan1", displayName = "WAN1", configured = true, enabled = true, online = false,
            defaultEgress = false, proto = "DHCP", ipv4 = "", ipv6 = "", gateway = "", gateway6 = "",
            dns = emptyList(), uptimeSeconds = 0, physicalPortId = 6, physicalPortName = "LAN2/WAN1",
            linkSpeedMbps = 0, totalDownloadBytes = 2048, trafficSource = "flow_audit",
        )
        val item = routerWanInfoItems(wan).single { it.label == "累计流量" }
        assertEquals("↓2 KB", item.value)
        assertFalse(item.value.contains("↑0"))
    }
}
