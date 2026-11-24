package com.labprobe.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhoneSpeedTestNode(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val province: String = "",
    val city: String = "",
    val carrier: String = ""
)

data class PhoneSpeedTestNetworkInfo(
    val transport: String,
    val protocol: String,
    val label: String,
    val network: Network?
)

data class PhoneSpeedTestProgress(
    val phase: String = "idle",
    val down: List<Double> = emptyList(),
    val up: List<Double> = emptyList(),
    val currentDown: Double? = null,
    val currentUp: Double? = null,
    val avgDown: Double? = null,
    val peakDown: Double? = null,
    val avgUp: Double? = null,
    val peakUp: Double? = null,
    val latency: Double? = null,
    val jitter: Double? = null,
    val loss: Double? = null,
    val totalBytes: Long = 0L,
    val durationMs: Long = 0L,
    /** 两个方向各自真实采到的有效时长（已扣预热），曲线 X 轴直接采用。 */
    val downSpanMs: Long = 0L,
    val upSpanMs: Long = 0L,
    val stable: Boolean = false,
    val message: String = ""
) {
    val running: Boolean get() = phase == "latency" || phase == "download" || phase == "upload"
}

data class PhoneSpeedTestResult(
    val node: PhoneSpeedTestNode,
    val network: PhoneSpeedTestNetworkInfo,
    val downAvgMbps: Double?,
    val downPeakMbps: Double?,
    val upAvgMbps: Double?,
    val upPeakMbps: Double?,
    val latency: Double?,
    val jitter: Double?,
    val loss: Double?,
    val totalBytes: Long,
    val durationMs: Long,
    val stable: Boolean,
    val message: String,
    val samplesDown: List<Double> = emptyList(),
    val samplesUp: List<Double> = emptyList()
) {
    fun toHistory(): PhoneSpeedHistoryEntry = PhoneSpeedHistoryEntry(
        epoch = System.currentTimeMillis() / 1000L,
        nodeName = node.name,
        network = network.transport,
        protocol = network.protocol,
        downAvg = downAvgMbps,
        downPeak = downPeakMbps,
        upAvg = upAvgMbps,
        upPeak = upPeakMbps,
        latency = latency,
        jitter = jitter,
        loss = loss,
        totalBytes = totalBytes,
        durationMs = durationMs,
        stable = stable,
        message = message
    )
}

data class PhoneSpeedHistoryEntry(
    val epoch: Long = 0L,
    val nodeName: String = "",
    val network: String = "",
    val protocol: String = "",
    val downAvg: Double? = null,
    val downPeak: Double? = null,
    val upAvg: Double? = null,
    val upPeak: Double? = null,
    val latency: Double? = null,
    val jitter: Double? = null,
    val loss: Double? = null,
    val totalBytes: Long = 0L,
    val durationMs: Long = 0L,
    val stable: Boolean = false,
    val message: String = ""
)

internal data class PhoneLatencyResult(
    val latency: Double?,
    val jitter: Double?,
    val loss: Double
)

internal fun speedMbps(totalBytes: Long, elapsedMs: Long): Double {
    if (totalBytes <= 0L || elapsedMs <= 0L) return 0.0
    return totalBytes * 8.0 / elapsedMs / 1_000.0
}

internal fun phoneLatencyResult(values: List<Double>, failures: Int, attempts: Int): PhoneLatencyResult {
    val latency = values.takeIf { it.isNotEmpty() }?.average()
    val jitter = values.takeIf { it.size >= 2 }?.zipWithNext()?.map { (a, b) -> abs(b - a) }?.average()
    val loss = if (attempts <= 0) 0.0 else failures * 100.0 / attempts
    return PhoneLatencyResult(latency, jitter, loss)
}

internal data class PhoneDirectionResult(
    val samples: List<Double>,
    val averageMbps: Double,
    val peakMbps: Double,
    val stable: Boolean,
    val message: String,
    /** 去掉 2s 预热之后真正采了多少毫秒，X 轴用它而不是「点数 × 间隔」预估。 */
    val effectiveMs: Long = 0L
)

internal class SpeedStabilityTracker(
    private val warmupMs: Long = 2_000L,
    /** 每个方向至少 8 秒有效采样，预热不算在内。 */
    private val minEffectiveMs: Long = 8_000L,
    private val windowMs: Long = 3_000L,
    /** 单方向 15 秒封顶，包含 2 秒预热，所以有效采样最多 13 秒。 */
    private val maxTotalMs: Long = 15_000L,
    /** 看「近期平均速率是否还在明显增长」而不是 250ms 的瞬时抖动。 */
    private val growthLimit: Double = .12,
    /** 只在「大幅来回跳」时兜底，正常 10–20% 的真实波动照样能结束。 */
    private val dispersionLimit: Double = .35,
    private val stableMs: Long = 1_500L
) {
    data class Sample(val atMs: Long, val mbps: Double)
    data class Snapshot(
        val done: Boolean,
        val stable: Boolean,
        val averageMbps: Double,
        val peakMbps: Double,
        val reason: String
    )

    private val samples = mutableListOf<Sample>()
    private var stableSince: Long? = null

    fun add(elapsedMs: Long, mbps: Double): Snapshot {
        if (mbps.isFinite() && mbps >= 0.0) samples += Sample(elapsedMs, mbps)
        val effective = samples.filter { it.atMs > warmupMs }
        val average = effective.takeIf { it.isNotEmpty() }?.map { it.mbps }?.average() ?: 0.0
        val peak = effective.maxOfOrNull { it.mbps } ?: 0.0
        // 15 秒是包含预热的总时长；到点就结束，成绩照常用有效采样算。
        if (elapsedMs >= maxTotalMs) {
            return Snapshot(true, false, average, peak, "达到单方向时限，按已采样本计算")
        }
        if (elapsedMs - warmupMs < minEffectiveMs) {
            return Snapshot(false, false, average, peak, "正在采集有效样本")
        }
        // 相邻两个 3 秒窗口的平均吞吐：只要不再明显上升/下降就收工。
        // 真实波动留在曲线里，不再拿瞬时 CV<8% 把每次测速都拖到上限。
        val recent = samples.filter { it.atMs > warmupMs && it.atMs > elapsedMs - windowMs }.map { it.mbps }
        val previous = samples.filter {
            it.atMs > warmupMs && it.atMs <= elapsedMs - windowMs && it.atMs > elapsedMs - windowMs * 2
        }.map { it.mbps }
        if (recent.size < 4 || previous.size < 4) {
            return Snapshot(false, false, average, peak, "正在采集有效样本")
        }
        val recentAvg = recent.average()
        val previousAvg = previous.average()
        val change = if (previousAvg <= 0.0) 1.0 else abs(recentAvg - previousAvg) / previousAvg
        // 例如 120/20 来回横跳时两个窗口均值可能一样，不能误判成「已稳定」。
        val dispersion = if (recentAvg <= 0.0) 1.0 else
            sqrt(recent.sumOf { (it - recentAvg) * (it - recentAvg) } / recent.size) / recentAvg
        val settled = change < growthLimit && dispersion < dispersionLimit
        if (settled) {
            val since = stableSince ?: elapsedMs.also { stableSince = it }
            if (elapsedMs - since >= stableMs) {
                return Snapshot(true, true, average, peak, "采样充分，自动结束")
            }
        } else {
            stableSince = null
        }
        return Snapshot(false, false, average, peak, "正在确认速率平台")
    }
}

object GlobalSpeedProtocol {
    private const val MATCH_URL = "https://dlcv2.cnspeedtest.cn:8443/dataServer/mobilematch_many.php"
    private const val MAX_PROBE_CONCURRENCY = 3
    private const val PROBE_TIMEOUT_MS = 1_500L
    private const val PROBE_TOTAL_TIMEOUT_MS = 5_000L
    private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val DALVIK_UA = "Dalvik/2.1.0 (Linux; U; Android 14)"

    /**
     * 匹配接口在部分网络出口只返回 1 个节点，选它还连不上就整页像「测速坏了」。
     * 这些是接口自己下发过的公共节点，作为兜底名单与实时结果合并；实时结果永远排前面，
     * 所以按出口推荐的排序不会被兜底节点盖掉。
     */
    private val BaselineNodes = listOf(
        "1105|广东|深圳|深圳电信|113.108.72.2",
        "1101|广东|广州|广州电信|61.144.6.18",
        "3030|福建|福州|福州电信|61.154.8.38",
        "31791|江西|南昌|南昌电信|59.52.20.90",
        "301000301|湖南|长沙|长沙电信|222.247.30.246",
        "72|海南|海口|海口电信|202.100.215.94",
        "80|广西|南宁|南宁电信|113.12.69.218"
    ).map { row ->
        val parts = row.split("|")
        PhoneSpeedTestNode(
            id = parts[0], province = parts[1], city = parts[2], name = parts[3],
            host = parts[4], port = 65499, carrier = carrierOf(parts[3])
        )
    }

    @Volatile
    private var lastGoodNodes: List<PhoneSpeedTestNode> = emptyList()

    internal fun carrierOf(name: String): String = when {
        name.contains("电信") -> "电信"
        name.contains("联通") -> "联通"
        name.contains("移动") -> "移动"
        else -> ""
    }

    /** 服务端结果 + 兜底名单，按 id 去重；服务端顺序在前，保留它按出口做的推荐。 */
    internal fun mergeNodes(live: List<PhoneSpeedTestNode>): List<PhoneSpeedTestNode> {
        val merged = LinkedHashMap<String, PhoneSpeedTestNode>()
        (live + baselineFallback()).forEach { node ->
            if (node.id.isNotBlank() && node.host.isNotBlank()) merged.putIfAbsent(node.id, node)
        }
        return merged.values.toList()
    }

    private fun baselineFallback(): List<PhoneSpeedTestNode> = lastGoodNodes + BaselineNodes

    /**
     * 节点不可达时 HTTP 探测会瞬间全失败，看起来像「点一下就 100% 丢包」。
     * 开测前用与实际测速**完全相同**的 Network 绑定 HTTP 请求做探测：裸 TCP 能握手
     * 不代表 HTTP 层能收到响应（实测有出口只放 SYN、不放应用层数据），那种节点
     * 必须在这里被淘汰掉，否则选了它一开测就立刻失败。
     * 404 也算可达——只要服务端回了 HTTP 响应头就说明链路通。
     */
    internal suspend fun firstReachableNode(
        candidates: List<PhoneSpeedTestNode>,
        network: Network?
    ): PhoneSpeedTestNode? = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext null
        val limit = candidates.size.coerceAtMost(MAX_PROBE_CONCURRENCY)
        val overall = withTimeoutOrNull(PROBE_TOTAL_TIMEOUT_MS) {
            candidates.chunked(limit).firstNotNullOfOrNull { batch ->
                val probes = batch.map { node ->
                    async {
                        val ok = try {
                            withHttpConnection<Boolean>(network, URL("http://${node.host}:${node.port}/"), PROBE_TIMEOUT_MS.toInt()) { conn ->
                                conn.requestMethod = "GET"
                                conn.setRequestProperty("Cache-Control", "no-store")
                                conn.responseCode
                                true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                        node to ok
                    }
                }
                val results = probes.awaitAll()
                batch.firstOrNull { node -> results.first { it.first.id == node.id }.second }
            }
        }
        overall
    }

    suspend fun fetchNodes(network: Network?, preferIpv6: Boolean): List<PhoneSpeedTestNode> = withContext(Dispatchers.IO) {
        val query = listOf(
            "ip=", "network=4", "province=", "city=", "wifioper=", "mobileoperid=0",
            "ipv6=${if (preferIpv6) "1" else "0"}", "model=Android", "pkg=com.labprobe.app"
        ).joinToString("&")
        val live = try {
            withHttpConnection<List<PhoneSpeedTestNode>>(network, URL("$MATCH_URL?$query"), 12_000) { conn ->
                if (conn.responseCode != 200) emptyList()
                else {
                    val text = decodeBody(conn.inputStream.use { it.readBytes() })
                    val rows = JSONArray(text)
                    val seen = HashSet<String>()
                    buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            val id = row.optString("hostid").trim()
                            val host = row.optString("hostip").trim()
                            if (id.isBlank() || host.isBlank() || !seen.add(id)) continue
                            val name = row.optString("hostname").trim().ifBlank { id }
                            add(
                                PhoneSpeedTestNode(
                                    id = id,
                                    name = name,
                                    host = host,
                                    port = row.optString("port").trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 65499,
                                    province = row.optString("pname").trim(),
                                    city = row.optString("city").trim(),
                                    carrier = row.optString("oper").trim().ifBlank { carrierOf(name) }
                                )
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
        // 只有两个以上才算「按出口推荐成功」，一个节点多半是这台出口被服务端限制了。
        if (live.size >= 2) lastGoodNodes = live
        mergeNodes(live)
    }

    suspend fun openSession(network: Network?, node: PhoneSpeedTestNode): String = withContext(Dispatchers.IO) {
        val imei = "TS" + ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        val now = System.currentTimeMillis() / 1000L
        val token = md5Hex(
            md5Hex("model=Android&imei=$imei") +
                md5Hex("stime=$now&band=200&rand=12345555")
        )
        val query = listOf(
            "key=", "flag=true", "bandwidth=200", "model=Android", "imei=$imei",
            "time=$now", "app=globalspeed", "token=$token", "pkg=com.labprobe.app"
        ).joinToString("&")
        withHttpConnection(network, URL("http://${node.host}:${node.port}/speed/dovalid?$query"), 10_000) { conn ->
            conn.setRequestProperty("User-Agent", DALVIK_UA)
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            Regex("1-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .find(body)?.value ?: error("GlobalSpeed 会话被拒绝")
        }
    }

    private fun openHttp(network: Network?, url: URL, timeoutMs: Int): HttpURLConnection {
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.connectTimeout = timeoutMs.coerceAtMost(5_000)
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-store")
        conn.setRequestProperty("User-Agent", CHROME_UA)
        return conn
    }

    internal suspend fun <T> withHttpConnection(
        network: Network?,
        url: URL,
        timeoutMs: Int,
        block: (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        val conn = openHttp(network, url, timeoutMs)
        suspendCancellableCoroutine { continuation ->
            val worker = Thread {
                try {
                    val result = block(conn)
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally {
                    runCatching { conn.disconnect() }
                }
            }.apply {
                isDaemon = true
                name = "LabProbe-GlobalSpeed-HTTP"
                start()
            }
            continuation.invokeOnCancellation {
                runCatching { conn.disconnect() }
                worker.interrupt()
            }
        }
    }

    private fun decodeBody(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) bytes.toString(charset("GBK")) else utf8
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class PhoneSpeedTestEngine(private val context: Context) {
    suspend fun run(
        node: PhoneSpeedTestNode,
        networkInfo: PhoneSpeedTestNetworkInfo,
        onProgress: (PhoneSpeedTestProgress) -> Unit
    ): PhoneSpeedTestResult = coroutineScope {
        val startedAt = SystemClock.elapsedRealtime()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkChanged = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (networkInfo.network == null || network == networkInfo.network) networkChanged.set(true)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        try {
            onProgress(PhoneSpeedTestProgress(phase = "latency", message = "测量延迟"))
            val latency = measureLatency(networkInfo, node)
            if (latency.latency == null) {
                // 节点探测一个包都回不来，直接给出可操作的原因；别让用户看着按钮闪一下
                // 又回到等待状态，也别拿失败覆盖上一次的成绩。
                throw IllegalStateException("节点「${node.name}」无响应，请换一个节点或检查手机网络")
            }
            onProgress(
                PhoneSpeedTestProgress(
                    phase = "download",
                    latency = latency.latency,
                    jitter = latency.jitter,
                    loss = latency.loss,
                    message = "下载预热"
                )
            )
            val session = GlobalSpeedProtocol.openSession(networkInfo.network, node)
            val totalBytes = AtomicLong(0L)
            val maxBytes = trafficLimit(networkInfo.transport)
            val down = runDirection(
                upload = false, node = node, networkInfo = networkInfo, session = session,
                totalBytes = totalBytes, maxBytes = maxBytes, latency = latency,
                shouldStop = { networkChanged.get() }, onProgress = onProgress
            )
            if (down.message == "网络已切换") {
                return@coroutineScope result(node, networkInfo, down, null, latency, totalBytes.get(), startedAt, false, down.message)
            }
            onProgress(
                PhoneSpeedTestProgress(
                    phase = "upload",
                    down = down.samples,
                    currentDown = down.samples.lastOrNull(),
                    avgDown = down.averageMbps,
                    peakDown = down.peakMbps,
                    latency = latency.latency,
                    jitter = latency.jitter,
                    loss = latency.loss,
                    totalBytes = totalBytes.get(),
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    downSpanMs = down.effectiveMs,
                    stable = down.stable,
                    message = "上传预热"
                )
            )
            val up = runDirection(
                upload = true, node = node, networkInfo = networkInfo, session = session,
                totalBytes = totalBytes, maxBytes = maxBytes, latency = latency,
                downSpanMs = down.effectiveMs,
                shouldStop = { networkChanged.get() }, onProgress = onProgress
            )
            val message = when {
                networkChanged.get() -> "网络已切换，测速已停止"
                down.message == "节点未返回有效数据" || up.message == "节点未返回有效数据" -> "节点未返回有效数据"
                up.message == "达到流量保护上限" || down.message == "达到流量保护上限" -> "达到流量保护上限"
                !down.stable || !up.stable -> "测速完成，速度波动较大"
                else -> "测速完成"
            }
            result(
                node, networkInfo, down, up, latency, totalBytes.get(), startedAt,
                !networkChanged.get() && down.stable && up.stable, message
            )
                .also {
                    onProgress(
                        PhoneSpeedTestProgress(
                            phase = "complete",
                            down = down.samples,
                            up = up.samples,
                            currentDown = down.samples.lastOrNull(),
                            currentUp = up.samples.lastOrNull(),
                            avgDown = down.averageMbps,
                            peakDown = down.peakMbps,
                            avgUp = up.averageMbps,
                            peakUp = up.peakMbps,
                            latency = latency.latency,
                            jitter = latency.jitter,
                            loss = latency.loss,
                            totalBytes = totalBytes.get(),
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            downSpanMs = down.effectiveMs,
                            upSpanMs = up.effectiveMs,
                            stable = down.stable && up.stable,
                            message = message
                        )
                    )
                }
        } finally {
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }

    private suspend fun runDirection(
        upload: Boolean,
        node: PhoneSpeedTestNode,
        networkInfo: PhoneSpeedTestNetworkInfo,
        session: String,
        totalBytes: AtomicLong,
        maxBytes: Long,
        latency: PhoneLatencyResult,
        downSpanMs: Long = 0L,
        shouldStop: () -> Boolean,
        onProgress: (PhoneSpeedTestProgress) -> Unit
    ): PhoneDirectionResult = coroutineScope {
        val tracker = SpeedStabilityTracker()
        val recorded = mutableListOf<Double>()
        val baselineBytes = totalBytes.get()
        var effectiveStartAt: Long? = null
        var effectiveStartBytes = 0L
        val pool = TransferPool(networkInfo, node, session, upload, totalBytes)
        var connections = 4
        pool.start(connections)
        val startedAt = SystemClock.elapsedRealtime()
        // 250ms 的字节差会被内核发送缓冲「攒满—排空」切成锯齿：上行 4~8 条连接同时写，
        // write() 只是把数据交给内核，下一次采样可能一粒都没走。改用 1 秒滑动窗口里的
        // 实际吞吐，曲线 / 实时值 / 峰值 / 均速都用同一口径，真实波动照旧保留。
        val byteHistory = ArrayDeque<Pair<Long, Long>>()
        byteHistory.addLast(startedAt to baselineBytes)
        var earlyRate = 0.0
        var stable = false
        var average = 0.0
        var peak = 0.0
        var message = ""
        var effectiveMs = 0L
        try {
            while (isActive) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - startedAt
                val total = totalBytes.get()
                byteHistory.addLast(now to total)
                while (byteHistory.size > 1 && now - byteHistory.first().first > 1_000L) {
                    byteHistory.removeFirst()
                }
                val oldest = byteHistory.first()
                val window = (now - oldest.first).coerceAtLeast(1L)
                val instant = speedMbps(total - oldest.second, window)
                if (elapsed > 2_000L && effectiveStartAt == null) {
                    effectiveStartAt = now
                    effectiveStartBytes = total
                }
                val effectiveStart = effectiveStartAt
                effectiveMs = effectiveStart?.let { now - it } ?: 0L
                val effectiveAverage = effectiveStart?.let { speedMbps(total - effectiveStartBytes, effectiveMs) } ?: 0.0
                // 预热段既不画也不算；正式段里即使是 0 也要照实记录，那是真的停住了。
                if (elapsed > 2_000L) recorded += instant
                val decision = tracker.add(elapsed, instant)
                if (!upload) {
                    onProgress(
                        PhoneSpeedTestProgress(
                            phase = "download",
                            down = recorded.toList(),
                            currentDown = instant,
                            avgDown = effectiveAverage,
                            peakDown = decision.peakMbps,
                            latency = latency.latency,
                            jitter = latency.jitter,
                            loss = latency.loss,
                            totalBytes = total,
                            durationMs = elapsed,
                            downSpanMs = effectiveMs,
                            message = "下载测速"
                        )
                    )
                } else {
                    onProgress(
                        PhoneSpeedTestProgress(
                            phase = "upload",
                            up = recorded.toList(),
                            currentUp = instant,
                            avgUp = effectiveAverage,
                            peakUp = decision.peakMbps,
                            latency = latency.latency,
                            jitter = latency.jitter,
                            loss = latency.loss,
                            totalBytes = total,
                            durationMs = elapsed,
                            downSpanMs = downSpanMs,
                            upSpanMs = effectiveMs,
                            message = "上传测速"
                        )
                    )
                }
                if (elapsed >= 1_000L && connections == 4) {
                    if (earlyRate <= 0.0) earlyRate = instant
                    else if (instant > earlyRate * 1.10) {
                        pool.start(2); connections = 6
                    }
                } else if (elapsed >= 1_500L && connections == 6 && instant > earlyRate * 1.15) {
                    pool.start(2); connections = 8
                }
                if (total >= maxBytes) {
                    message = "达到流量保护上限"
                    break
                }
                if (shouldStop()) {
                    message = "网络已切换"
                    break
                }
                if (decision.done) {
                    stable = decision.stable
                    peak = decision.peakMbps
                    message = decision.reason
                    break
                }
            }
        } finally {
            pool.stop()
        }
        val effectiveStart = effectiveStartAt
        average = if (effectiveStart != null && effectiveMs > 0L) {
            speedMbps(totalBytes.get() - effectiveStartBytes, effectiveMs)
        } else {
            recorded.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        }
        peak = recorded.maxOrNull() ?: 0.0
        if (totalBytes.get() - baselineBytes < 64L * 1024L) {
            message = "节点未返回有效数据"
            stable = false
        }
        PhoneDirectionResult(recorded, average, peak, stable, message, effectiveMs)
    }

    private suspend fun measureLatency(networkInfo: PhoneSpeedTestNetworkInfo, node: PhoneSpeedTestNode): PhoneLatencyResult {
        val values = mutableListOf<Double>()
        var failures = 0
        var attempts = 0
        while (attempts < 5 && failures < 3) {
            currentCoroutineContext().ensureActive()
            attempts++
            val started = SystemClock.elapsedRealtime()
            try {
                val elapsed = GlobalSpeedProtocol.withHttpConnection(
                    networkInfo.network,
                    URL("http://${node.host}:${node.port}/"),
                    4_000,
                ) { conn ->
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Cache-Control", "no-store")
                    conn.responseCode
                    (SystemClock.elapsedRealtime() - started).toDouble()
                }
                values += elapsed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failures++
            }
        }
        return phoneLatencyResult(values, failures, attempts)
    }

    private fun result(
        node: PhoneSpeedTestNode,
        network: PhoneSpeedTestNetworkInfo,
        down: PhoneDirectionResult,
        up: PhoneDirectionResult?,
        latency: PhoneLatencyResult,
        totalBytes: Long,
        startedAt: Long,
        stable: Boolean,
        message: String
    ) = PhoneSpeedTestResult(
        node = node,
        network = network,
        downAvgMbps = down.averageMbps.takeIf { it > 0.0 },
        downPeakMbps = down.peakMbps.takeIf { it > 0.0 },
        upAvgMbps = up?.averageMbps?.takeIf { it > 0.0 },
        upPeakMbps = up?.peakMbps?.takeIf { it > 0.0 },
        latency = latency.latency,
        jitter = latency.jitter,
        loss = latency.loss,
        totalBytes = totalBytes,
        durationMs = SystemClock.elapsedRealtime() - startedAt,
        stable = stable,
        message = message,
        samplesDown = down.samples,
        samplesUp = up?.samples.orEmpty()
    )

    private fun trafficLimit(transport: String): Long = when {
        transport.contains("蜂窝") -> 800L * 1024L * 1024L
        transport.contains("VPN") -> 2L * 1024L * 1024L * 1024L
        else -> 4L * 1024L * 1024L * 1024L
    }
}

private class TransferPool(
    private val networkInfo: PhoneSpeedTestNetworkInfo,
    private val node: PhoneSpeedTestNode,
    session: String,
    private val upload: Boolean,
    private val totalBytes: AtomicLong
) {
    private val active = AtomicBoolean(true)
    private val sockets = ConcurrentLinkedQueue<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private val uuid = session.removePrefix("1-")
    private val keyHeader = session

    fun start(count: Int) {
        repeat(count) {
            jobs += scope.launch { worker() }
        }
    }

    fun stop() {
        active.set(false)
        sockets.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private suspend fun worker() {
        while (active.get() && scope.isActive) {
            var socket: Socket? = null
            try {
                socket = connect()
                if (upload) uploadWorker(socket) else downloadWorker(socket)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (active.get()) delay(150)
            } finally {
                socket?.let {
                    sockets.remove(it)
                    runCatching { it.close() }
                }
            }
        }
    }

    private fun connect(): Socket {
        val addresses = networkInfo.network?.getAllByName(node.host) ?: InetAddress.getAllByName(node.host)
        val preferIpv6 = networkInfo.protocol.contains("IPv6")
        val address = if (preferIpv6) {
            addresses.firstOrNull { it is Inet6Address } ?: addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        } else {
            addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        }
        if (!active.get() || !scope.isActive) throw CancellationException("测速已取消")
        val socket = Socket()
        sockets.add(socket)
        try {
            networkInfo.network?.bindSocket(socket)
            if (upload) {
                // 默认的发送缓冲攒一大坨再排空，write() 只算「交给内核」不算真的发出去，
                // 250ms 取值就会看到规律锯齿。收窄到 64KB 让写入速度贴着链路实际吞吐走。
                socket.sendBufferSize = 64 * 1024
            }
            if (!active.get() || !scope.isActive) throw CancellationException("测速已取消")
            socket.connect(InetSocketAddress(address, node.port), 5_000)
            socket.soTimeout = 5_000
            return socket
        } catch (failure: Throwable) {
            sockets.remove(socket)
            runCatching { socket.close() }
            throw failure
        }
    }

    private fun downloadWorker(socket: Socket) {
        val now = System.currentTimeMillis() / 1000L
        val request = "GET /speed/File(1G).dl?r=$now&key=$uuid HTTP/1.1\r\n" +
            "Accept: */*\r\nConnection: close\r\nUser-Agent: $DOWNLOAD_UA\r\n" +
            "Host:${node.host}:${node.port}\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        if (!readOk(socket)) return
        val buffer = ByteArray(64 * 1024)
        while (active.get() && scope.isActive) {
            val read = socket.getInputStream().read(buffer)
            if (read <= 0) break
            totalBytes.addAndGet(read.toLong())
        }
    }

    private fun uploadWorker(socket: Socket) {
        val now = System.currentTimeMillis() / 1000L
        val boundary = "00content0boundary00"
        val header = "POST /speed/doAnalsLoad.do HTTP/1.1\r\n" +
            "Connection: close\r\nCache-Control: no-cache\r\nCharset: UTF-8\r\n" +
            "Key: $keyHeader\r\n" +
            "Content-Type: multipart/form-data;boundary=$boundary\r\n" +
            "User-Agent: $UPLOAD_UA\r\nHost:${node.host}:${node.port}\r\n" +
            "Content-Length: 900000000\r\n\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"upload\";filename=\"SPEED_$now\"\r\n\r\n"
        socket.getOutputStream().write(header.toByteArray(Charsets.ISO_8859_1))
        val chunk = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }
        while (active.get() && scope.isActive) {
            socket.getOutputStream().write(chunk)
            totalBytes.addAndGet(chunk.size.toLong())
        }
    }

    private fun readOk(socket: Socket): Boolean {
        val buffer = ByteArray(4096)
        var size = 0
        socket.soTimeout = 10_000
        while (size < buffer.size) {
            val read = socket.getInputStream().read(buffer, size, buffer.size - size)
            if (read <= 0) break
            size += read
            if (size >= 4 && String(buffer, 0, size, Charsets.ISO_8859_1).contains("\r\n\r\n")) break
        }
        return String(buffer, 0, size, Charsets.ISO_8859_1).startsWith("HTTP/1.1 200") ||
            String(buffer, 0, size, Charsets.ISO_8859_1).startsWith("HTTP/1.0 200")
    }

    private companion object {
        const val DOWNLOAD_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.60 Safari/537.36"
        const val UPLOAD_UA = "Dalvik/1.6.0 (Linux; U; Android 4.2.2; GT-I9505 Build/JDQ39)"
    }
}

fun currentPhoneSpeedTestNetwork(context: Context): PhoneSpeedTestNetworkInfo? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(network) ?: return null
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        else -> "其他"
    }
    val link = cm.getLinkProperties(network)
    val hasIpv6 = link?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true
    val protocol = if (hasIpv6) "IPv4 / IPv6" else "IPv4"
    return PhoneSpeedTestNetworkInfo(transport, protocol, "$transport · $protocol", network)
}