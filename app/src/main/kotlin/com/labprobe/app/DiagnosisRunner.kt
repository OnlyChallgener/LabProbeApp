package com.labprobe.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground orchestration only. Probes own their existing bounded network calls. */
class DiagnosisRunner(private val timeoutMs: Long = 12_000L) {
    companion object {
        // Shared across runs: slow legacy calls cannot accumulate on repeated retries.
        private val inFlight = Semaphore(3)
    }
    suspend fun run(
        probe: suspend (DiagnosisCheck) -> DiagnosisItem,
        clock: () -> Long = System::currentTimeMillis,
        onProgress: (DiagnosisProgress) -> Unit,
    ): DiagnosisResult {
        require(timeoutMs > 0)
        // Legacy synchronous HTTP/ICMP calls may finish after cancellation. Isolate
        // their lifetime from the UI deadline; no late result is ever published.
        val workerJob = SupervisorJob()
        val workers = CoroutineScope(Dispatchers.IO + workerJob)
        val items = mutableListOf<DiagnosisItem>()
        try {
            for (check in DiagnosisCheck.entries) {
                currentCoroutineContext().ensureActive()
                onProgress(DiagnosisProgress(items.toList(), current = check, running = true))
                val pending = workers.async { inFlight.withPermit { probe(check) } }
                val item = try {
                    withTimeoutOrNull(timeoutMs) { pending.await() } ?: DiagnosisItem(
                        check, HealthStatus.UNKNOWN, "检测超时", "本项未完成，已继续下一项",
                        listOf("在 ${timeoutMs / 1_000} 秒内未获得完整结果；超时不等于服务故障。"),
                    )
                } catch (_: TimeoutCancellationException) {
                    // A reused tool's own timeout is a failed check, not a user stop.
                    // Parent cancellation is still rethrown by ensureActive below.
                    DiagnosisItem(check, HealthStatus.UNKNOWN, "检测超时", "本项未完成，已继续下一项")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Never surface raw exception messages: URLs can contain credentials.
                    DiagnosisItem(check, HealthStatus.UNKNOWN, "暂无法检测", "未获得有效结果，已继续下一项",
                        listOf("检测调用失败；请在对应工具页重试。"))
                } finally {
                    pending.cancel()
                }
                currentCoroutineContext().ensureActive()
                items += item.copy(check = check, checkedAt = clock())
                onProgress(DiagnosisProgress(items.toList(), running = true))
            }
            val result = DiagnosisRules.summarize(items, clock())
            onProgress(DiagnosisProgress(items.toList(), result = result))
            return result
        } finally {
            workerJob.cancel()
        }
    }
}

object DiagnosisRules {
    fun summarize(items: List<DiagnosisItem>, completedAt: Long): DiagnosisResult {
        val checks = items.associateBy { it.check }
        fun status(check: DiagnosisCheck) = checks[check]?.status ?: HealthStatus.UNKNOWN
        fun failed(check: DiagnosisCheck) = status(check) in setOf(HealthStatus.WARNING, HealthStatus.ERROR)
        val core = listOf(DiagnosisCheck.GATEWAY, DiagnosisCheck.INTERNET, DiagnosisCheck.DNS)
        val overall = when {
            items.any { it.status == HealthStatus.ERROR } -> HealthStatus.ERROR
            items.any { it.status == HealthStatus.WARNING } -> HealthStatus.WARNING
            core.all { status(it) == HealthStatus.GOOD } -> HealthStatus.GOOD
            else -> HealthStatus.UNKNOWN
        }
        val conclusion = when {
            status(DiagnosisCheck.INTERNET) == HealthStatus.ERROR && status(DiagnosisCheck.GATEWAY) == HealthStatus.GOOD ->
                "局域网正常，但手机到互联网的连接存在异常。"
            status(DiagnosisCheck.GATEWAY) == HealthStatus.ERROR ->
                "手机到默认网关的连接存在异常，请先检查当前接入网络。"
            status(DiagnosisCheck.INTERNET) == HealthStatus.ERROR ->
                "手机到互联网的连接存在异常；局域网状态仍需确认。"
            failed(DiagnosisCheck.INTERNET) ->
                if (status(DiagnosisCheck.GATEWAY) == HealthStatus.GOOD) "局域网正常，互联网连接质量存在异常。" else "互联网连接质量存在异常，请查看连通性检测结果。"
            failed(DiagnosisCheck.DNS) ->
                if (status(DiagnosisCheck.INTERNET) == HealthStatus.GOOD) "互联网可达，但 DNS 解析存在异常。" else "DNS 解析存在异常，可能影响通过域名访问服务。"
            failed(DiagnosisCheck.GATEWAY) -> "默认网关响应需要关注，请查看局域网检测结果。"
            failed(DiagnosisCheck.IPV6) && status(DiagnosisCheck.ROUTER) == HealthStatus.GOOD ->
                "Router 在线，但手机所在网络的 IPv6 可用性存在异常。"
            failed(DiagnosisCheck.RELAY) -> "Relay 状态异常，依赖它的路由采集或远程功能可能受影响。"
            failed(DiagnosisCheck.WIREGUARD) -> "WireGuard 连接状态异常，远程访问可能受影响。"
            failed(DiagnosisCheck.ROUTER) -> "Router 状态异常，请检查路由器及其管理连接。"
            failed(DiagnosisCheck.IPV6) -> "当前网络的 IPv6 可用性存在异常。"
            failed(DiagnosisCheck.DEVICES) -> "设备实时数据存在异常，当前设备列表可能不是最新状态。"
            failed(DiagnosisCheck.STUN) -> "STUN 映射状态存在异常，依赖映射的远程访问可能受影响。"
            overall == HealthStatus.GOOD -> "局域网、互联网和 DNS 检测正常。"
            else -> "检测证据尚不完整，暂无法确认整体网络状态。"
        }
        val unknown = DiagnosisCheck.entries.count { status(it) == HealthStatus.UNKNOWN }
        val suffix = if (unknown > 0) " 另有 $unknown 项未确认或未启用，未据此判定故障。" else ""
        return DiagnosisResult(items, overall, conclusion + suffix, completedAt)
    }
}
