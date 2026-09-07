package com.labprobe.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

/** Read-only adapters to the existing tools. The coordinator owns timeout/cancellation. */
class DiagnosisProbes(
    private val context: Context,
    private val prefs: AppPrefs,
    private val state: AppState,
) {
    private val connectivity get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    suspend fun check(check: DiagnosisCheck): DiagnosisItem = when (check) {
        DiagnosisCheck.ROUTER -> router()
        DiagnosisCheck.GATEWAY -> gateway()
        DiagnosisCheck.INTERNET -> internet()
        DiagnosisCheck.DNS -> dns()
        DiagnosisCheck.IPV6 -> ipv6()
        DiagnosisCheck.DEVICES -> devices()
        DiagnosisCheck.RELAY -> relay()
        DiagnosisCheck.STUN -> stun()
        DiagnosisCheck.WIREGUARD -> wireGuard()
    }

    private fun item(check: DiagnosisCheck, status: HealthStatus, metric: String, explanation: String, vararg details: String) =
        DiagnosisItem(check, status, metric, explanation, details.toList(), System.currentTimeMillis())

    private fun noHub(check: DiagnosisCheck) = item(check, HealthStatus.UNKNOWN, "未配置", "请先连接 Hub", "观测点：Hub；未配置 Hub 地址")

    /** Existing endpoints use both epoch seconds and epoch milliseconds. */
    private fun ageMillis(epoch: Long): Long? {
        if (epoch <= 0L) return null
        val milliseconds = if (epoch < 1_000_000_000_000L) epoch * 1000L else epoch
        return System.currentTimeMillis() - milliseconds
    }

    private suspend fun router(): DiagnosisItem {
        if (prefs.hub.isBlank()) return noHub(DiagnosisCheck.ROUTER)
        val snapshot = RouterControlApi(prefs).hubStatus()
        val connected = snapshot.connected || snapshot.sessionConnected
        // lastSuccessAt can be a session creation time on compatible Hubs.
        // Verify freshness with the existing realtime sample, not the login time.
        val sample = if (connected) try {
            LiteRealtimeApi(prefs).router()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null } else null
        val age = sample?.optLong("sampleAgeMs", -1L)
        val evidence = sample != null && sample.optLong("sampleEpochMs", 0L) > 0L && age != null && age >= 0L
        val fresh = evidence && age!! <= 10_000L && !sample!!.optBoolean("stale", false)
        val good = connected && snapshot.dataAvailable && fresh
        val unconfigured = snapshot.state in setOf("checking", "unconfigured", "not_configured", "disabled")
        val insufficient = connected && (!snapshot.dataAvailable || !evidence)
        return item(DiagnosisCheck.ROUTER,
            if (good) HealthStatus.GOOD else if (unconfigured || insufficient) HealthStatus.UNKNOWN else HealthStatus.WARNING,
            if (good) "在线" else "待确认",
            if (good) "Hub 与 Router 控制链路正常" else if (connected) "Router 会话在线，控制数据仍待确认" else "Hub 暂未确认 Router 在线",
            "观测点：Hub → Router", "会话已连接：$connected", "控制数据可用：${snapshot.dataAvailable}",
            "实时采样年龄：${age ?: -1L} ms", "实时数据新鲜度已确认：$fresh")
    }

    private suspend fun gateway(): DiagnosisItem {
        val cm = connectivity
        val network = cm?.activeNetwork
        val caps = network?.let { cm?.getNetworkCapabilities(it) }
        if (caps == null || (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            return item(DiagnosisCheck.GATEWAY, HealthStatus.UNKNOWN, "未检测", "当前网络不是可确认的本地局域网", "观测点：手机当前网络；移动网络或 VPN 不能代表家庭局域网")
        }
        val address = network?.let { cm?.getLinkProperties(it) }?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            ?: return item(DiagnosisCheck.GATEWAY, HealthStatus.UNKNOWN, "无网关", "未获取到本地默认网关", "观测点：手机当前网络")
        val ms = runIcmpSeries(address, 1, 1000L, 1000, SystemClock.elapsedRealtime()) {}.points.firstOrNull()?.ms
        return item(DiagnosisCheck.GATEWAY, if (ms == null || ms >= 50) HealthStatus.WARNING else HealthStatus.GOOD,
            ms?.let { "$it ms" } ?: "未响应",
            if (ms == null) "网关未回应 Ping，可能限制 ICMP" else if (ms >= 50) "局域网响应偏慢" else "本地网关响应正常",
            "观测点：手机 → 默认网关 ${address.hostAddress}", "协议：ICMP；单次探测，不代表长期丢包率")
    }

    private suspend fun internet(): DiagnosisItem {
        if (connectivity?.activeNetwork == null) return item(DiagnosisCheck.INTERNET, HealthStatus.ERROR, "无连接", "手机当前没有可用网络", "观测点：手机网络连接状态")
        val samples = probeEndpoints(listOf("223.5.5.5" to 53, "1.1.1.1" to 443))
        val best = samples.mapNotNull { it.second }.minOrNull()
        return item(DiagnosisCheck.INTERNET, if (best == null || best >= 200) HealthStatus.WARNING else HealthStatus.GOOD,
            best?.let { "$it ms" } ?: "未响应",
            if (best == null) "外网探测目标未响应，需进一步确认" else if (best >= 200) "外网存在明显延迟" else "手机可连接外网",
            "观测点：手机当前网络（可能经过 VPN），不是 Router WAN", "指标：最快 TCP 建连耗时",
            *samples.map { "${it.first}：${it.second?.let { ms -> "$ms ms" } ?: "未响应"}" }.toTypedArray())
    }

    private suspend fun dns(): DiagnosisItem = withContext(Dispatchers.IO) {
        val cm = connectivity
        val properties = cm?.activeNetwork?.let { cm?.getLinkProperties(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && properties?.isPrivateDnsActive == true) {
            return@withContext item(DiagnosisCheck.DNS, HealthStatus.UNKNOWN, "加密 DNS", "系统私有 DNS 已启用，本次未验证加密解析",
                "观测点：手机 DNS 配置；既有 UDP 53 探测不能代表系统私有 DNS，未据此判定解析故障。")
        }
        val servers = getLocalDnsServers(context).take(2)
        if (servers.isEmpty()) return@withContext item(DiagnosisCheck.DNS, HealthStatus.UNKNOWN, "无数据", "未取得当前网络 DNS", "观测点：手机网络 DNS 配置")
        val observations = servers.map { server ->
            val start = SystemClock.elapsedRealtime()
            val records = DnsWire.query("www.qq.com", server, 1)
            Triple(server, records, SystemClock.elapsedRealtime() - start)
        }
        val successful = observations.filter { it.second.isNotEmpty() }
        return@withContext item(DiagnosisCheck.DNS,
            if (successful.size == observations.size) HealthStatus.GOOD else HealthStatus.WARNING,
            successful.minOfOrNull { it.third }?.let { "$it ms" } ?: "未响应",
            if (successful.isEmpty()) "DNS 直接查询未返回记录" else if (successful.size < observations.size) "部分 DNS 查询未响应" else "域名解析正常",
            "观测点：手机 → 当前网络 DNS；www.qq.com / A / UDP 53",
            "系统私有 DNS 可能使用加密通道；本项不代表其解析结果",
            *observations.map { "${it.first}：${it.second.joinToString().ifBlank { "无记录或超时" }}（${it.third} ms）" }.toTypedArray())
    }

    private suspend fun ipv6(): DiagnosisItem {
        val samples = probeEndpoints(listOf("2400:3200::1" to 53, "2606:4700:4700::1111" to 443))
        val best = samples.mapNotNull { it.second }.minOrNull()
        return item(DiagnosisCheck.IPV6, if (best == null) HealthStatus.WARNING else HealthStatus.GOOD,
            if (best == null) "IPv6 未验证" else "$best ms",
            if (best == null) "未检测到可用 IPv6 外网连接" else "IPv6 外网连接可用",
            "观测点：手机当前网络；IPv4 结果见互联网项", "使用既有 TCP 探测；数字 IPv6 地址，独立于 DNS",
            *samples.map { "${it.first}：${it.second?.let { ms -> "$ms ms" } ?: "未响应"}" }.toTypedArray())
    }

    private suspend fun probeEndpoints(endpoints: List<Pair<String, Int>>): List<Pair<String, Int?>> = withContext(Dispatchers.IO) {
        endpoints.map { (host, port) -> "$host:$port" to tcpConnectOnce(InetAddress.getByName(host), port, 1200) }
    }

    private suspend fun devices(): DiagnosisItem {
        if (prefs.hub.isBlank()) return noHub(DiagnosisCheck.DEVICES)
        val sample = LiteRealtimeApi(prefs).devices()
        val epoch = sample.optLong("sampleEpochMs", 0L)
        val age = sample.optLong("sampleAgeMs", -1L)
        val valid = epoch > 0L && age >= 0L
        val fresh = valid && age <= 15_000L && !sample.optBoolean("stale", false)
        val count = sample.optInt("onlineDeviceCount", -1)
        val realtime = fresh && state.mqttConnected
        return item(DiagnosisCheck.DEVICES, if (!valid) HealthStatus.UNKNOWN else if (realtime) HealthStatus.GOOD else HealthStatus.WARNING,
            if (count >= 0) "$count 台" else "已读取",
            if (realtime) "设备实时数据正常" else if (fresh) "当前仅快照可读，实时链路未连接" else "设备数据新鲜度尚未确认",
            "观测点：Hub 设备实时接口", "采样时间：$epoch；采样年龄：$age ms",
            "App 实时链路已连接：${state.mqttConnected}", "数据新鲜不等于逐台服务可访问")
    }

    private suspend fun relay(): DiagnosisItem {
        if (prefs.hub.isBlank()) return noHub(DiagnosisCheck.RELAY)
        val agent = PortMapApi(prefs).list().agent
        val known = agent.lastSeenEpoch > 0 || agent.lastSeenAt.isNotBlank()
        val observedAge = ageMillis(agent.lastSeenEpoch)
        val insufficient = !known || observedAge == null || observedAge < 0L || agent.ageSeconds < 0L
        val online = agent.online && agent.ageSeconds in 0L..60L && observedAge != null && observedAge in 0L..60_000L
        return item(DiagnosisCheck.RELAY, if (insufficient) HealthStatus.UNKNOWN else if (online) HealthStatus.GOOD else HealthStatus.WARNING,
            if (insufficient) "未确认" else if (online) "在线" else "未连接",
            if (online) "LabRelay 心跳正常" else if (insufficient) "LabRelay 心跳新鲜度尚未确认" else "LabRelay 连接需要检查",
            "观测点：Hub → LabRelay Agent", "Hub 报告在线：${agent.online}", "心跳年龄：${agent.ageSeconds} 秒", "最后上报时间戳：${agent.lastSeenEpoch}")
    }

    private suspend fun stun(): DiagnosisItem {
        if (prefs.hub.isBlank()) return noHub(DiagnosisCheck.STUN)
        val snapshot = StunApi(prefs).list()
        val enabled = snapshot.rules.filter { it.enabled }
        if (!snapshot.rulesLoaded || enabled.isEmpty()) return item(DiagnosisCheck.STUN, HealthStatus.UNKNOWN,
            if (snapshot.rulesLoaded) "未启用" else "未同步", "没有可诊断的已启用 STUN 映射", "观测点：Hub STUN 状态；不会自动创建或开启规则")
        val ready = enabled.count { it.ready }
        val good = snapshot.agentOnline && ready == enabled.size
        return item(DiagnosisCheck.STUN, if (good) HealthStatus.GOOD else HealthStatus.WARNING,
            "$ready / ${enabled.size}", if (good) "STUN 映射状态正常" else "部分 STUN 映射尚未就绪",
            "观测点：Hub → Agent 映射状态；不等于已验证公网服务可访问",
            *enabled.mapIndexed { index, rule -> "映射 ${index + 1}：就绪 ${rule.ready}；映射新鲜 ${rule.runtime.mappingFresh}；报告错误 ${rule.runtime.lastError.isNotBlank()}" }.toTypedArray())
    }

    private suspend fun wireGuard(): DiagnosisItem {
        val profiles = withContext(Dispatchers.IO) { WireGuardProfileStore(context, prefs).load() }
        if (profiles.isEmpty()) return item(DiagnosisCheck.WIREGUARD, HealthStatus.UNKNOWN, "未配置", "尚未配置 WireGuard", "观测点：手机 WireGuard；不会自动建立隧道")
        val runtime = WireGuardTunnelController.get(context, prefs).status()
        if (!runtime.running) return item(DiagnosisCheck.WIREGUARD,
            if (runtime.lastError.isBlank()) HealthStatus.UNKNOWN else HealthStatus.WARNING, "未连接",
            if (runtime.lastError.isBlank()) "WireGuard 当前未启用" else "WireGuard 状态读取异常",
            "观测点：手机 WireGuard", "后台报告错误：${runtime.lastError.isNotBlank()}")
        val age = System.currentTimeMillis() - runtime.latestHandshakeAt
        val fresh = runtime.latestHandshakeAt > 0 && age in 0..180_000L
        return item(DiagnosisCheck.WIREGUARD, if (fresh) HealthStatus.GOOD else HealthStatus.WARNING,
            if (fresh) "已握手" else "等待握手", if (fresh) "WireGuard 隧道握手正常" else "隧道已启用，尚未确认近期握手",
            "观测点：手机 WireGuard 后端；不代表所有内网服务可用", "最近握手：${runtime.latestHandshakeAt}",
            "接收 ${runtime.receivedBytes} bytes；发送 ${runtime.sentBytes} bytes")
    }
}
