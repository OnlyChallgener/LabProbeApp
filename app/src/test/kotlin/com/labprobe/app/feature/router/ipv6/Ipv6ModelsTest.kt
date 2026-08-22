package com.labprobe.app.feature.router.ipv6

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6ModelsTest {
    @Test
    fun parsesStatusConfigAndClientsFromHubContract() {
        val status = parseIpv6Status(
            JSONObject()
                .put("connected", true)
                .put("proto", "dhcpv6")
                .put("address", "2409:8a50::20/64")
                .put("prefix", "2409:8a50:10::/60")
                .put("gateway", "fe80::1")
                .put("dns", JSONArray(listOf("2400:3200::1", "2400:3200:baba::1")))
        )
        val config = parseIpv6Config(
            JSONObject()
                .put(
                    "wan",
                    JSONObject()
                        .put("proto", "dhcpv6")
                        .put("ifname", "@wan")
                        .put("dns", JSONArray(listOf("2400:3200::1")))
                        .put("dnsType", "manual")
                        .put("relay", false)
                )
                .put(
                    "lan",
                    JSONObject()
                        .put("ip6assign", 64)
                        .put("dhcpv6Server", true)
                        .put("slaac", true)
                        .put("dhcpv6Type", "DHCPv6+SLAAC")
                        .put("ra", true)
                        .put("ra_management", "1")
                        .put("leasetime6", 120)
                )
        )
        val clients = parseDhcpv6Clients(
            JSONObject().put(
                "clients",
                JSONArray().put(
                    JSONObject()
                        .put("hostname", "fnos")
                        .put("ipv6", "2409:8a50:10::1c3b")
                        .put("leasetime", 88)
                        .put("duid", "00:04:c9:8b")
                )
            )
        )

        assertTrue(status.connected)
        assertEquals(listOf("2400:3200::1", "2400:3200:baba::1"), status.dns)
        assertEquals(Ipv6DnsMode.MANUAL, Ipv6FormState.from(config).dnsMode)
        assertEquals("fnos", clients.single().hostname)
        assertEquals(88, clients.single().leaseMinutes)
    }

    @Test
    fun serializesOnlyEditableIpv6FieldsForHubMerge() {
        val form = Ipv6FormState(
            wanMode = WanIpv6Mode.RELAY,
            dnsMode = Ipv6DnsMode.MANUAL,
            manualDns = "2400:3200::1, 2400:3200:baba::1",
            dhcpv6Server = false,
            slaac = true,
            ra = true,
            prefixLength = "60",
            leaseMinutes = "240",
        )

        val root = form.toJson()
        val wan = root.getJSONObject("wan")
        val lan = root.getJSONObject("lan")

        assertNull(form.validationError)
        assertEquals("relay", wan.getString("proto"))
        assertTrue(wan.getBoolean("relay"))
        assertEquals("manual", wan.getString("dnsType"))
        assertEquals(2, wan.getJSONArray("dns").length())
        assertFalse(lan.getBoolean("dhcpv6Server"))
        assertTrue(lan.getBoolean("slaac"))
        assertEquals(60, lan.getInt("ip6assign"))
        assertEquals(240, lan.getInt("leasetime6"))
        assertEquals("0", lan.getString("ra_management"))
    }

    @Test
    fun rejectsInvalidManualDnsBeforeSave() {
        val form = Ipv6FormState(
            dnsMode = Ipv6DnsMode.MANUAL,
            manualDns = "not-an-ipv6-address",
        )

        assertTrue(form.validationError.orEmpty().contains("DNS 地址无效"))
    }
}
