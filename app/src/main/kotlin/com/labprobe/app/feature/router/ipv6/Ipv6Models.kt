package com.labprobe.app.feature.router.ipv6

import com.labprobe.app.normalizeIpv6
import org.json.JSONArray
import org.json.JSONObject

data class Ipv6Status(
    val connected: Boolean = false,
    val proto: String = "",
    val address: String = "",
    val prefix: String = "",
    val gateway: String = "",
    val dns: List<String> = emptyList(),
)

data class WanIpv6Config(
    val proto: String = "dhcpv6",
    val ifname: String = "@wan",
    val dns: List<String> = emptyList(),
    val dnsType: String = "auto",
    val relay: Boolean = false,
)

data class LanIpv6Config(
    val prefixLength: Int = 64,
    val dhcpv6Server: Boolean = true,
    val slaac: Boolean = true,
    val dhcpv6Type: String = "DHCPv6+SLAAC",
    val ra: Boolean = true,
    val raManagement: String = "1",
    val leaseMinutes: Int = 120,
    val relay: Boolean = false,
)

data class Ipv6Config(
    val wan: WanIpv6Config = WanIpv6Config(),
    val lan: LanIpv6Config = LanIpv6Config(),
)

data class Dhcpv6Client(
    val hostname: String = "",
    val ipv6: String = "",
    val leaseMinutes: Int = 0,
    val duid: String = "",
)

enum class WanIpv6Mode(val title: String) {
    DHCPV6("DHCPv6"),
    RELAY("IPv6 中继"),
}

enum class Ipv6DnsMode(val title: String) {
    AUTO("自动"),
    MANUAL("手动"),
}

data class Ipv6FormState(
    val wanMode: WanIpv6Mode = WanIpv6Mode.DHCPV6,
    val dnsMode: Ipv6DnsMode = Ipv6DnsMode.AUTO,
    val manualDns: String = "",
    val dhcpv6Server: Boolean = true,
    val slaac: Boolean = true,
    val ra: Boolean = true,
    val prefixLength: String = "64",
    val leaseMinutes: String = "120",
) {
    val validationError: String?
        get() {
            val prefix = prefixLength.trim().toIntOrNull()
                ?: return "Prefix 长度必须是数字"
            if (prefix !in 1..128) return "Prefix 长度必须在 1 到 128 之间"
            val lease = leaseMinutes.trim().toIntOrNull()
                ?: return "Lease 时间必须是数字"
            if (lease !in 1..10080) return "Lease 时间必须在 1 到 10080 分钟之间"
            if (dnsMode == Ipv6DnsMode.MANUAL) {
                val rows = dnsTokens()
                if (rows.isEmpty()) return "请至少填写一个 IPv6 DNS 地址"
                rows.firstOrNull { normalizeIpv6(it) == null }?.let { return "IPv6 DNS 地址无效：$it" }
                if (rows.size > 4) return "IPv6 DNS 最多填写 4 个地址"
            }
            return null
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("wan", JSONObject().apply {
            put("proto", if (wanMode == WanIpv6Mode.RELAY) "relay" else "dhcpv6")
            put("relay", wanMode == WanIpv6Mode.RELAY)
            put("dnsType", if (dnsMode == Ipv6DnsMode.MANUAL) "manual" else "auto")
            put("dns", JSONArray(if (dnsMode == Ipv6DnsMode.MANUAL) dnsTokens() else emptyList<String>()))
        })
        put("lan", JSONObject().apply {
            put("dhcpv6Server", dhcpv6Server)
            put("slaac", slaac)
            put("ra", ra)
            put("ip6assign", prefixLength.trim().toInt())
            put("leasetime6", leaseMinutes.trim().toInt())
            put("ra_management", if (dhcpv6Server) "1" else "0")
        })
    }

    private fun dnsTokens(): List<String> = manualDns
        .split(Regex("[,;\\s]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    companion object {
        fun from(config: Ipv6Config): Ipv6FormState = Ipv6FormState(
            wanMode = if (config.wan.relay || config.wan.proto.equals("relay", true)) WanIpv6Mode.RELAY else WanIpv6Mode.DHCPV6,
            dnsMode = if (config.wan.dnsType.equals("manual", true) || config.wan.dnsType.equals("admin", true)) Ipv6DnsMode.MANUAL else Ipv6DnsMode.AUTO,
            manualDns = config.wan.dns.joinToString(", "),
            dhcpv6Server = config.lan.dhcpv6Server,
            slaac = config.lan.slaac,
            ra = config.lan.ra,
            prefixLength = config.lan.prefixLength.toString(),
            leaseMinutes = config.lan.leaseMinutes.toString(),
        )
    }
}

internal fun parseIpv6Status(data: JSONObject): Ipv6Status = Ipv6Status(
    connected = data.optBoolean("connected", false),
    proto = data.optString("proto").trim(),
    address = data.optString("address").trim(),
    prefix = data.optString("prefix").trim(),
    gateway = data.optString("gateway").trim(),
    dns = data.stringList("dns"),
)

internal fun parseIpv6Config(data: JSONObject): Ipv6Config {
    val wan = data.optJSONObject("wan") ?: JSONObject()
    val lan = data.optJSONObject("lan") ?: JSONObject()
    return Ipv6Config(
        wan = WanIpv6Config(
            proto = wan.optString("proto", "dhcpv6").trim(),
            ifname = wan.optString("ifname", "@wan").trim(),
            dns = wan.stringList("dns"),
            dnsType = wan.optString("dnsType", "auto").trim(),
            relay = wan.optBoolean("relay", false),
        ),
        lan = LanIpv6Config(
            prefixLength = lan.optInt("ip6assign", 64),
            dhcpv6Server = lan.optBoolean("dhcpv6Server", true),
            slaac = lan.optBoolean("slaac", true),
            dhcpv6Type = lan.optString("dhcpv6Type", "DHCPv6+SLAAC").trim(),
            ra = lan.optBoolean("ra", true),
            raManagement = lan.optString("ra_management", "1").trim(),
            leaseMinutes = lan.optInt("leasetime6", 120),
            relay = lan.optBoolean("relay", false),
        ),
    )
}

internal fun parseDhcpv6Clients(data: JSONObject): List<Dhcpv6Client> {
    val rows = data.optJSONArray("clients") ?: JSONArray()
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            add(
                Dhcpv6Client(
                    hostname = row.optString("hostname").trim(),
                    ipv6 = row.optString("ipv6").trim(),
                    leaseMinutes = row.optInt("leasetime", 0).coerceAtLeast(0),
                    duid = row.optString("duid").trim(),
                )
            )
        }
    }
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key)
    if (array != null) {
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }
    return optString(key)
        .split(Regex("[,;\\s]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
}
