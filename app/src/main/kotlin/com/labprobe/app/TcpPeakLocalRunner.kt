package com.labprobe.app

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

internal class TcpPeakLocalRunner {
    private val lock = Any()
    private val stopRequested = AtomicBoolean(false)
    private val releaseEpoch = AtomicLong(0L)
    private val held = mutableMapOf(
        TcpPeakFamily.IPV4 to Collections.synchronizedList(mutableListOf<Socket>()),
        TcpPeakFamily.IPV6 to Collections.synchronizedList(mutableListOf<Socket>())
    )
    private val opening = Collections.synchronizedSet(mutableSetOf<Socket>())

    suspend fun run(
        taskId: String,
        rawConfig: TcpPeakConfig,
        onSnapshot: suspend (TcpPeakSnapshot) -> Unit
    ): TcpPeakSnapshot {
        val config = rawConfig.normalized()
        stopRequested.set(false)
        releaseAll()
        val startedEpochMs = System.currentTimeMillis()
        var snapshot = TcpPeakSnapshot(
            taskId = taskId,
            side = TcpPeakSide.APP,
            state = "running",
            status = "正在准备本机测试",
            resourcesReleased = true,
            releaseStatus = "尚未创建测试连接",
            startedEpochMs = startedEpochMs,
            logs = listOf("本机 APP 已开始 TCP 峰值连接数测试")
        )
        onSnapshot(snapshot)
        var failed = false
        val reasons = mutableListOf<String>()
        try {
            val families = when (config.family) {
                TcpPeakFamily.IPV4 -> listOf(TcpPeakFamily.IPV4)
                TcpPeakFamily.IPV6 -> listOf(TcpPeakFamily.IPV6)
                TcpPeakFamily.BOTH -> listOf(TcpPeakFamily.IPV4, TcpPeakFamily.IPV6)
            }
            for (family in families) {
                if (stopRequested.get()) break
                val result = runFamily(config, family, snapshot, onSnapshot)
                snapshot = result.snapshot
                reasons += result.reason
                failed = failed || result.failed
            }
        } catch (cancelled: CancellationException) {
            stopRequested.set(true)
        } catch (error: Throwable) {
            failed = true
            reasons += localErrorZh(error)
        } finally {
            val released = releaseAll() >= 0 && synchronized(lock) { held.values.all(List<Socket>::isEmpty) && opening.isEmpty() }
            val stopped = stopRequested.get()
            val reason = when {
                stopped -> "用户停止测试"
                reasons.isNotEmpty() -> reasons.joinToString("；")
                else -> "测试未产生结果"
            }
            snapshot = snapshot.copy(
                state = when {
                    stopped -> "stopped"
                    failed -> "failed"
                    else -> "completed"
                },
                status = when {
                    stopped -> "测试已停止"
                    failed -> "测试失败"
                    else -> "测试已完成"
                },
                finishReason = reason,
                resourcesReleased = released,
                releaseStatus = if (released) "本机测试连接已全部释放" else "本机连接释放未完成",
                finishedEpochMs = System.currentTimeMillis(),
                logs = (snapshot.logs + "测试结束：$reason").takeLast(80)
            )
            onSnapshot(snapshot)
        }
        return snapshot
    }

    fun requestStop() {
        stopRequested.set(true)
        releaseEpoch.incrementAndGet()
        closeOpeningSockets()
        held.keys.forEach(::requestCloseFamily)
    }

    private suspend fun runFamily(
        config: TcpPeakConfig,
        family: TcpPeakFamily,
        initial: TcpPeakSnapshot,
        onSnapshot: suspend (TcpPeakSnapshot) -> Unit
    ): FamilyResult {
        releaseFamily(family)
        val expectedEpoch = releaseEpoch.get()
        val addresses = resolve(config.host, family)
        if (addresses.isEmpty()) {
            val reason = "${family.label} 目标地址解析失败"
            val metric = TcpPeakMetric(status = "解析失败", finishReason = reason)
            val snapshot = initial.withMetric(family, metric).copy(
                status = reason,
                resourcesReleased = true,
                releaseStatus = "未创建测试连接",
                logs = (initial.logs + reason).takeLast(80)
            )
            onSnapshot(snapshot)
            return FamilyResult(snapshot, reason, failed = true)
        }

        val (safeTarget, fdSoftLimit, baselineFd) = localSafeTarget(config.targetConnections)
        var snapshot = initial.copy(
            state = "running",
            status = "${family.label} 正在建立连接",
            resourcesReleased = false,
            releaseStatus = "本机测试连接正在使用资源",
            logs = (initial.logs + "${family.label} 安全上限 $safeTarget（FD 软上限 $fdSoftLimit，测试前占用 $baselineFd）").takeLast(80)
        ).withMetric(family, TcpPeakMetric(status = "正在建立连接"))
        onSnapshot(snapshot)

        val pendingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pending = ArrayDeque<Deferred<LocalOpenResult>>()
        val started = SystemClock.elapsedRealtime()
        var lastTick = started
        var lastReport = started
        var lastReportSuccess = 0L
        var lastGrowth = started
        var tokenCarry = 0.0
        var addressIndex = 0
        var success = 0L
        var failure = 0L
        var peak = 0
        var consecutiveFailures = 0
        var finishReason = "达到设定连接数"
        val maximumPending = (config.cps * 4).coerceIn(500, 4_000)
        val recentOutcomes = ArrayDeque<Boolean>()

        try {
            while (currentCoroutineContext().isActive && !stopRequested.get()) {
                val now = SystemClock.elapsedRealtime()
                var qualityStopReason: String? = null
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val deferred = iterator.next()
                    if (!deferred.isCompleted) continue
                    iterator.remove()
                    val result = runCatching { deferred.await() }.getOrElse { LocalOpenResult(false, localErrorZh(it)) }
                    if (result.success) {
                        success++
                        consecutiveFailures = 0
                        lastGrowth = now
                        recentOutcomes.addLast(true)
                    } else if (!result.discarded) {
                        failure++
                        consecutiveFailures++
                        recentOutcomes.addLast(false)
                        if (result.reason == "本机 FD 已耗尽") {
                            finishReason = "达到本机 APP 的 FD 安全上限"
                        }
                    }
                    while (recentOutcomes.size > TCP_PEAK_RECENT_OUTCOME_WINDOW) recentOutcomes.removeFirst()
                    qualityStopReason = tcpPeakFailureStopReason(
                        consecutiveFailures = consecutiveFailures,
                        recentOutcomes = recentOutcomes,
                        noGrowthMs = now - lastGrowth
                    )
                    if (qualityStopReason != null) break
                }
                if (qualityStopReason != null) {
                    finishReason = qualityStopReason
                    break
                }
                val current = heldCount(family)
                peak = maxOf(peak, current)
                if (current >= safeTarget || current >= config.targetConnections) {
                    finishReason = if (safeTarget < config.targetConnections) "达到本机 APP 的 FD 安全上限" else "达到设定连接数"
                    break
                }
                if (finishReason.contains("FD 安全上限")) break
                if (now - started >= config.maxDurationSeconds * 1_000L) {
                    finishReason = "达到最长测试时间"
                    break
                }
                if (failure > 0 && now - lastGrowth >= 4_000L && current >= safeTarget * 9 / 10) {
                    finishReason = "连接数已稳定在当前峰值"
                    break
                }
                if (failure >= 100 && success == 0L && now - started >= 5_000L) {
                    finishReason = "目标持续拒绝连接或连接超时"
                    break
                }

                val elapsedTick = (now - lastTick).coerceAtLeast(0L)
                lastTick = now
                val load = (current + pending.size).toDouble() / safeTarget.coerceAtLeast(1).toDouble()
                val scale = when {
                    load >= .95 -> .20
                    load >= .80 -> .50
                    else -> 1.0
                }
                tokenCarry += config.cps * scale * elapsedTick / 1_000.0
                val room = (safeTarget - current - pending.size).coerceAtLeast(0)
                val launches = floor(tokenCarry).toInt()
                    .coerceAtMost(room)
                    .coerceAtMost((maximumPending - pending.size).coerceAtLeast(0))
                    .coerceAtMost(tcpPeakRemainingFailureBudget(consecutiveFailures, pending.size))
                tokenCarry -= launches
                repeat(launches) {
                    val address = addresses[addressIndex++ % addresses.size]
                    pending += pendingScope.async {
                        openOne(address, config.port, config.connectTimeoutMs, family, expectedEpoch)
                    }
                }

                if (now - lastReport >= 1_000L) {
                    val cps = ((success - lastReportSuccess) * 1_000L / (now - lastReport).coerceAtLeast(1L)).toInt()
                    lastReportSuccess = success
                    lastReport = now
                    val metric = TcpPeakMetric(
                        current = current,
                        peak = peak,
                        success = success,
                        failure = failure,
                        cps = cps,
                        status = "正在建立连接",
                        elapsedMs = now - started
                    )
                    snapshot = snapshot.withMetric(family, metric).copy(status = "${family.label} 正在建立连接")
                    onSnapshot(snapshot)
                }
                delay(40L)
            }
        } finally {
            releaseEpoch.compareAndSet(expectedEpoch, expectedEpoch + 1)
            pendingScope.cancel()
            snapshot = snapshot.copy(
                state = "releasing",
                status = "${family.label} 正在释放连接",
                releaseStatus = "正在取消连接并释放本机 socket"
            )
            onSnapshot(snapshot)
            requestCloseFamily(family)
            closeOpeningSockets()
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        val released = heldCount(family) == 0 && opening.isEmpty()
        val finalMetric = TcpPeakMetric(
            current = 0,
            peak = peak,
            success = success,
            failure = failure,
            cps = 0,
            status = if (stopRequested.get()) "已停止" else "已完成",
            elapsedMs = elapsed,
            finishReason = if (stopRequested.get()) "用户停止测试" else finishReason
        )
        snapshot = snapshot.withMetric(family, finalMetric).copy(
            state = if (stopRequested.get()) "stop_requested" else "running",
            status = if (stopRequested.get()) "正在停止" else "${family.label} 测试结束",
            resourcesReleased = released,
            releaseStatus = if (released) "${family.label} 连接已释放" else "${family.label} 连接仍在释放",
            logs = (snapshot.logs + "${family.label} 测试结束：${finalMetric.finishReason}，峰值 $peak").takeLast(80)
        )
        onSnapshot(snapshot)
        return FamilyResult(snapshot, "${family.label} ${finalMetric.finishReason}", failed = !released)
    }

    private fun resolve(host: String, family: TcpPeakFamily): List<InetAddress> = runCatching {
        InetAddress.getAllByName(host).filter {
            (family == TcpPeakFamily.IPV4 && it is Inet4Address) ||
                (family == TcpPeakFamily.IPV6 && it is Inet6Address)
        }.distinctBy(InetAddress::getHostAddress)
    }.getOrDefault(emptyList())

    private fun openOne(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
        family: TcpPeakFamily,
        expectedEpoch: Long
    ): LocalOpenResult {
        if (stopRequested.get() || releaseEpoch.get() != expectedEpoch) return LocalOpenResult(discarded = true)
        val socket = Socket()
        opening += socket
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            opening -= socket
            synchronized(lock) {
                if (stopRequested.get() || releaseEpoch.get() != expectedEpoch) {
                    fastClose(socket)
                    LocalOpenResult(discarded = true)
                } else {
                    held.getValue(family) += socket
                    LocalOpenResult(success = true)
                }
            }
        } catch (error: Throwable) {
            opening -= socket
            fastClose(socket)
            LocalOpenResult(success = false, reason = localErrorZh(error))
        }
    }

    private fun localSafeTarget(requested: Int): Triple<Int, Int, Int> {
        val softLimit = runCatching {
            Regex("Max open files\\s+(\\d+)").find(File("/proc/self/limits").readText())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.getOrNull()?.coerceAtLeast(1_024) ?: 32_768
        val baseline = runCatching { File("/proc/self/fd").list()?.size ?: 0 }.getOrDefault(64).coerceAtLeast(32)
        val safe = (softLimit - baseline - 128).coerceAtMost(32_640).coerceAtLeast(1)
        return Triple(requested.coerceAtMost(safe), softLimit, baseline)
    }

    private fun heldCount(family: TcpPeakFamily): Int = synchronized(lock) { held.getValue(family).size }

    private fun requestCloseFamily(family: TcpPeakFamily) {
        val sockets = synchronized(lock) {
            held.getValue(family).toList().also { held.getValue(family).clear() }
        }
        closeSockets(sockets)
    }

    private fun releaseFamily(family: TcpPeakFamily): Int {
        val count = heldCount(family)
        requestCloseFamily(family)
        return count
    }

    private fun releaseAll(): Int {
        val count = synchronized(lock) { held.values.sumOf(List<Socket>::size) } + synchronized(opening) { opening.size }
        releaseEpoch.incrementAndGet()
        held.keys.forEach(::requestCloseFamily)
        closeOpeningSockets()
        return count
    }

    private fun closeOpeningSockets() {
        val sockets = synchronized(opening) { opening.toList().also { opening.clear() } }
        closeSockets(sockets)
    }

    private fun closeSockets(sockets: List<Socket>) {
        sockets.forEach(::fastClose)
    }

    private fun fastClose(socket: Socket) {
        runCatching { socket.setSoLinger(true, 0) }
        runCatching { socket.close() }
    }

    private data class LocalOpenResult(
        val success: Boolean = false,
        val reason: String = "",
        val discarded: Boolean = false
    )

    private data class FamilyResult(
        val snapshot: TcpPeakSnapshot,
        val reason: String,
        val failed: Boolean
    )
}

private fun TcpPeakSnapshot.withMetric(family: TcpPeakFamily, metric: TcpPeakMetric): TcpPeakSnapshot = when (family) {
    TcpPeakFamily.IPV4 -> copy(ipv4 = metric)
    TcpPeakFamily.IPV6 -> copy(ipv6 = metric)
    TcpPeakFamily.BOTH -> this
}

private fun localErrorZh(error: Throwable): String {
    val text = error.message.orEmpty().lowercase()
    return when {
        error is UnknownHostException -> "目标地址解析失败"
        error is SocketTimeoutException -> "连接超时"
        error is SocketException && ("too many open files" in text || "emfile" in text) -> "本机 FD 已耗尽"
        "connection refused" in text -> "目标拒绝连接"
        "network is unreachable" in text || "no route" in text -> "当前网络无法到达目标"
        error is CancellationException -> "测试已取消"
        else -> "连接失败"
    }
}
