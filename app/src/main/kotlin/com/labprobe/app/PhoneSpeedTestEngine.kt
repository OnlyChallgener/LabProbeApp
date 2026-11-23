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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

internal data class PhoneDirectionResult(
    val samples: List<Double>,
    val averageMbps: Double,
    val peakMbps: Double,
    val stable: Boolean,
    val message: String
)

internal class SpeedStabilityTracker(
    private val warmupMs: Long = 2_000L,
    private val minEffectiveMs: Long = 6_000L,
    private val windowMs: Long = 4_000L,
    private val maxMs: Long = 25_000L,
    private val sampleIntervalMs: Long = 250L,
    private val cvLimit: Double = .08,
    private val adjacentChangeLimit: Double = .05,
    private val stableMs: Long = 3_000L
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
        if (elapsedMs >= maxMs) {
            return Snapshot(true, false, average, peak, "达到单方向时限，速度波动较大")
        }
        val enough = effective.size >= (minEffectiveMs / sampleIntervalMs).toInt().coerceAtLeast(1)
        if (!enough) return Snapshot(false, false, average, peak, "正在采集有效样本")
        val recent = samples.filter { it.atMs >= elapsedMs - windowMs }
        if (recent.size < 4) return Snapshot(false, false, average, peak, "正在采集有效样本")
        val values = recent.map { it.mbps }
        val mean = values.average()
        val cv = if (mean <= 0.0) 1.0 else sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size) / mean
        val lastTwo = samples.filter { it.atMs in (elapsedMs - 2_000L)..elapsedMs }.map { it.mbps }
        val previousTwo = samples.filter { it.atMs in (elapsedMs - 4_000L)..(elapsedMs - 2_000L) }.map { it.mbps }
        val lastAvg = lastTwo.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val previousAvg = previousTwo.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val change = if (lastAvg <= 0.0 || previousAvg <= 0.0) 1.0 else abs(lastAvg - previousAvg) / maxOf(lastAvg, previousAvg)
        val stableNow = cv < cvLimit && change < adjacentChangeLimit
        if (stableNow) {
            val since = stableSince ?: elapsedMs.also { stableSince = it }
            if (elapsedMs - since >= stableMs) {
                return Snapshot(true, true, average, peak, "速度已稳定，自动结束")
            }
        } else {
            stableSince = null
        }
        return Snapshot(false, false, average, peak, "正在确认稳定窗口")
    }
}

object GlobalSpeedProtocol {
    private const val MATCH_URL = "https://dlcv2.cnspeedtest.cn:8443/dataServer/mobilematch_many.php"
    private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val DALVIK_UA = "Dalvik/2.1.0 (Linux; U; Android 14)"

    suspend fun fetchNodes(network: Network?, preferIpv6: Boolean): List<PhoneSpeedTestNode> = withContext(Dispatchers.IO) {
        val query = listOf(
            "ip=", "network=4", "province=", "city=", "wifioper=", "mobileoperid=0",
            "ipv6=${if (preferIpv6) "1" else "0"}", "model=Android", "pkg=com.labprobe.app"
        ).joinToString("&")
        val conn = openHttp(network, URL("$MATCH_URL?$query"), 12_000)
        try {
            if (conn.responseCode != 200) return@withContext emptyList()
            val bytes = conn.inputStream.use { it.readBytes() }
            val text = decodeBody(bytes)
            val rows = JSONArray(text)
            val seen = HashSet<String>()
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val id = row.optString("hostid").trim()
                    val host = row.optString("hostip").trim()
                    if (id.isBlank() || host.isBlank() || !seen.add(id)) continue
                    add(
                        PhoneSpeedTestNode(
                            id = id,
                            name = row.optString("hostname").trim().ifBlank { id },
                            host = host,
                            port = row.optString("port").trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 65499,
                            province = row.optString("pname").trim(),
                            city = row.optString("city").trim(),
                            carrier = row.optString("oper").trim()
                        )
                    )
                }
            }
        } finally {
            conn.disconnect()
        }
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
        val conn = openHttp(network, URL("http://${node.host}:${node.port}/speed/dovalid?$query"), 10_000)
        conn.setRequestProperty("User-Agent", DALVIK_UA)
        try {
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            Regex("1-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .find(body)?.value ?: error("GlobalSpeed 会话被拒绝")
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttp(network: Network?, url: URL, timeoutMs: Int): HttpURLConnection {
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-store")
        conn.setRequestProperty("User-Agent", CHROME_UA)
        return conn
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
        var networkChanged = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (networkInfo.network == null || network == networkInfo.network) networkChanged = true
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        try {
            onProgress(PhoneSpeedTestProgress(phase = "latency", message = "测量延迟"))
            val latency = measureLatency(networkInfo, node)
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
                shouldStop = { networkChanged }, onProgress = onProgress
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
                    stable = down.stable,
                    message = "上传预热"
                )
            )
            val up = runDirection(
                upload = true, node = node, networkInfo = networkInfo, session = session,
                totalBytes = totalBytes, maxBytes = maxBytes, latency = latency,
                shouldStop = { networkChanged }, onProgress = onProgress
            )
            val message = when {
                networkChanged -> "网络已切换，测速已停止"
                down.message == "节点未返回有效数据" || up.message == "节点未返回有效数据" -> "节点未返回有效数据"
                up.message == "达到流量保护上限" || down.message == "达到流量保护上限" -> "达到流量保护上限"
                !down.stable || !up.stable -> "测速完成，速度波动较大"
                else -> "测速完成"
            }
            result(
                node, networkInfo, down, up, latency, totalBytes.get(), startedAt,
                !networkChanged && down.stable && up.stable, message
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
        shouldStop: () -> Boolean,
        onProgress: (PhoneSpeedTestProgress) -> Unit
    ): PhoneDirectionResult = coroutineScope {
        val tracker = SpeedStabilityTracker()
        val recorded = mutableListOf<Double>()
        val baselineBytes = totalBytes.get()
        val pool = TransferPool(networkInfo, node, session, upload, totalBytes)
        var connections = 4
        pool.start(connections)
        val startedAt = SystemClock.elapsedRealtime()
        var lastBytes = totalBytes.get()
        var lastAt = startedAt
        var earlyRate = 0.0
        var stable = false
        var average = 0.0
        var peak = 0.0
        var message = ""
        try {
            while (isActive) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - startedAt
                val total = totalBytes.get()
                val instant = if (now > lastAt) (total - lastBytes) * 8.0 / (now - lastAt) / 1_000.0 else 0.0
                lastBytes = total
                lastAt = now
                if (elapsed > 2_000L && instant > 0.0) recorded += instant
                val decision = tracker.add(elapsed, instant)
                if (!upload) {
                    onProgress(
                        PhoneSpeedTestProgress(
                            phase = "download",
                            down = recorded.toList(),
                            currentDown = instant,
                            avgDown = decision.averageMbps,
                            peakDown = decision.peakMbps,
                            latency = latency.latency,
                            jitter = latency.jitter,
                            loss = latency.loss,
                            totalBytes = total,
                            durationMs = elapsed,
                            message = "下载测速"
                        )
                    )
                } else {
                    onProgress(
                        PhoneSpeedTestProgress(
                            phase = "upload",
                            up = recorded.toList(),
                            currentUp = instant,
                            avgUp = decision.averageMbps,
                            peakUp = decision.peakMbps,
                            latency = latency.latency,
                            jitter = latency.jitter,
                            loss = latency.loss,
                            totalBytes = total,
                            durationMs = elapsed,
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
                    average = decision.averageMbps
                    peak = decision.peakMbps
                    message = decision.reason
                    break
                }
            }
        } finally {
            pool.stop()
        }
        if (average <= 0.0) {
            average = recorded.takeIf { it.isNotEmpty() }?.average() ?: 0.0
            peak = recorded.maxOrNull() ?: 0.0
        }
        if (totalBytes.get() - baselineBytes < 64L * 1024L) {
            message = "节点未返回有效数据"
            stable = false
        }
        PhoneDirectionResult(recorded, average, peak, stable, message)
    }

    private fun measureLatency(networkInfo: PhoneSpeedTestNetworkInfo, node: PhoneSpeedTestNode): PhoneLatencyResult {
        val values = mutableListOf<Double>()
        var failures = 0
        repeat(5) {
            val started = SystemClock.elapsedRealtime()
            try {
                val conn = (networkInfo.network?.openConnection(URL("http://${node.host}:${node.port}/"))
                    ?: URL("http://${node.host}:${node.port}/").openConnection()) as HttpURLConnection
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Cache-Control", "no-store")
                conn.responseCode
                values += (SystemClock.elapsedRealtime() - started).toDouble()
                conn.disconnect()
            } catch (_: Throwable) {
                failures++
            }
        }
        val latency = values.takeIf { it.isNotEmpty() }?.average()
        val jitter = values.takeIf { it.size >= 2 }?.zipWithNext()?.map { (a, b) -> abs(b - a) }?.average()
        return PhoneLatencyResult(latency, jitter, failures * 100.0 / 5.0)
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
                sockets.add(socket)
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
        val socket = Socket()
        networkInfo.network?.bindSocket(socket)
        socket.connect(InetSocketAddress(address, node.port), 5_000)
        socket.soTimeout = 5_000
        return socket
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