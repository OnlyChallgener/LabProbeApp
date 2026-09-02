package com.labprobe.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class TcpPeakSide(val label: String) {
    APP("本机 APP"),
    RELAY("Relay 宿主机")
}

enum class TcpPeakFamily(val wireValue: String, val label: String) {
    IPV4("ipv4", "IPv4"),
    IPV6("ipv6", "IPv6"),
    BOTH("both", "分别测试")
}

data class TcpPeakConfig(
    val side: TcpPeakSide = TcpPeakSide.APP,
    val host: String = "",
    val port: Int = 443,
    val family: TcpPeakFamily = TcpPeakFamily.BOTH,
    val targetConnections: Int = 10_000,
    val cps: Int = 500,
    val extremeMode: Boolean = false,
    val connectTimeoutMs: Int = 1_500,
    val maxDurationSeconds: Int = 180
) {
    fun normalized(): TcpPeakConfig = copy(
        host = host.trim().removePrefix("[").removeSuffix("]"),
        port = port.coerceIn(1, 65_535),
        targetConnections = targetConnections.coerceIn(1, 65_535),
        cps = cps.coerceIn(1, 10_000),
        extremeMode = extremeMode && side == TcpPeakSide.RELAY,
        connectTimeoutMs = connectTimeoutMs.coerceIn(300, 10_000),
        maxDurationSeconds = maxDurationSeconds.coerceIn(10, 300)
    )

    fun validationError(): String? = when {
        host.trim().isBlank() -> "请输入测试目标主机"
        host.contains("://") || host.any(Char::isWhitespace) -> "测试目标主机格式无效"
        port !in 1..65_535 -> "目标端口必须是 1–65535"
        targetConnections !in 1..65_535 -> "连接量程必须是 1–65535"
        cps !in 1..10_000 -> "CPS 必须是 1–10000"
        extremeMode && side != TcpPeakSide.RELAY -> "极限模式仅支持 Relay 宿主机"
        else -> null
    }
}

/** One-shot handoff from an AI confirmation to the canonical screen controller. */
data class TcpPeakPendingAiCommand(
    val id: String,
    val config: TcpPeakConfig,
    val expiresAtEpochMs: Long
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("expiresAtEpochMs", expiresAtEpochMs)
        .put("host", config.host)
        .put("port", config.port)
        .put("family", config.family.wireValue)
        .put("targetConnections", config.targetConnections)
        .put("cps", config.cps)
        .put("extremeMode", config.extremeMode)
        .put("connectTimeoutMs", config.connectTimeoutMs)
        .put("maxDurationSeconds", config.maxDurationSeconds)
        .toString()

    companion object {
        fun fromJson(raw: String, nowEpochMs: Long = System.currentTimeMillis()): TcpPeakPendingAiCommand? {
            val row = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val expiresAt = row.optLong("expiresAtEpochMs")
            if (expiresAt <= nowEpochMs) return null
            val family = TcpPeakFamily.entries.firstOrNull {
                it.wireValue == row.optString("family").lowercase(Locale.ROOT)
            } ?: return null
            val config = TcpPeakConfig(
                side = TcpPeakSide.APP,
                host = row.optString("host"),
                port = row.optInt("port"),
                family = family,
                targetConnections = row.optInt("targetConnections"),
                cps = row.optInt("cps"),
                extremeMode = row.optBoolean("extremeMode", false),
                connectTimeoutMs = row.optInt("connectTimeoutMs", 1_500),
                maxDurationSeconds = row.optInt("maxDurationSeconds", 180)
            )
            if (config.validationError() != null) return null
            return TcpPeakPendingAiCommand(
                id = row.optString("id").ifBlank { return null },
                config = config.normalized(),
                expiresAtEpochMs = expiresAt
            )
        }
    }
}

data class TcpPeakMetric(
    val current: Int = 0,
    val peak: Int = 0,
    val success: Long = 0,
    val failure: Long = 0,
    val cps: Int = 0,
    val status: String = "待测试",
    val elapsedMs: Long = 0,
    val finishReason: String = ""
) {
    companion object {
        fun fromJson(value: JSONObject?): TcpPeakMetric {
            val row = value ?: JSONObject()
            return TcpPeakMetric(
                current = row.optInt("current").coerceAtLeast(0),
                peak = row.optInt("peak").coerceAtLeast(0),
                success = row.optLong("success").coerceAtLeast(0),
                failure = row.optLong("failure").coerceAtLeast(0),
                cps = row.optInt("cps").coerceAtLeast(0),
                status = row.optString("status").ifBlank { "等待状态更新" },
                elapsedMs = row.optLong("elapsedMs").coerceAtLeast(0),
                finishReason = row.optString("finishReason")
            )
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("current", current)
        .put("peak", peak)
        .put("success", success)
        .put("failure", failure)
        .put("cps", cps)
        .put("status", status)
        .put("elapsedMs", elapsedMs)
        .put("finishReason", finishReason)
}

data class TcpPeakSnapshot(
    val taskId: String = "",
    val side: TcpPeakSide = TcpPeakSide.APP,
    val state: String = "idle",
    val status: String = "待测试",
    val finishReason: String = "",
    val ipv4: TcpPeakMetric = TcpPeakMetric(),
    val ipv6: TcpPeakMetric = TcpPeakMetric(),
    val logs: List<String> = emptyList(),
    val conntrackPeak: Long = 0,
    val cpuPeak: Double = 0.0,
    val memoryMinAvailableMb: Long = 0,
    val resourcesReleased: Boolean = true,
    val releaseStatus: String = "当前没有待释放资源",
    val startedEpochMs: Long = 0,
    val finishedEpochMs: Long = 0,
    val error: String = ""
) {
    val active: Boolean get() = state in ACTIVE_STATES
    val terminal: Boolean get() = state in TERMINAL_STATES
    val elapsedMs: Long get() = maxOf(ipv4.elapsedMs, ipv6.elapsedMs)

    companion object {
        val ACTIVE_STATES = setOf("queued", "accepted", "running", "stop_requested", "releasing")
        val TERMINAL_STATES = setOf("completed", "stopped", "failed", "interrupted")

        fun fromRelayJson(root: JSONObject): TcpPeakSnapshot {
            val row = root.optJSONObject("task") ?: root
            if (row.length() == 0) return TcpPeakSnapshot(side = TcpPeakSide.RELAY)
            val state = row.optString("state", "interrupted").lowercase(Locale.ROOT)
            val logs = row.optJSONArray("logs") ?: JSONArray()
            return TcpPeakSnapshot(
                taskId = row.optString("id"),
                side = TcpPeakSide.RELAY,
                state = state,
                status = row.optString("status").ifBlank { tcpPeakStateZh(state) },
                finishReason = row.optString("finishReason"),
                ipv4 = TcpPeakMetric.fromJson(row.optJSONObject("ipv4")),
                ipv6 = TcpPeakMetric.fromJson(row.optJSONObject("ipv6")),
                logs = (0 until logs.length()).mapNotNull { index ->
                    logs.optString(index).trim().takeIf(String::isNotBlank)
                }.takeLast(80),
                conntrackPeak = row.optLong("conntrackPeak").coerceAtLeast(0),
                cpuPeak = row.optDouble("cpuPeak", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0,
                memoryMinAvailableMb = row.optLong("memoryMinAvailableMb").coerceAtLeast(0),
                resourcesReleased = row.optBoolean("resourcesReleased", state !in ACTIVE_STATES),
                releaseStatus = row.optString("releaseStatus").ifBlank { "等待资源状态更新" },
                startedEpochMs = row.optLong("startedEpoch").coerceAtLeast(0) * 1_000L,
                finishedEpochMs = row.optLong("finishedEpoch").coerceAtLeast(0) * 1_000L
            )
        }
    }
}

data class TcpPeakTrendPoint(
    val epochSecond: Long,
    val ipv4Current: Int,
    val ipv6Current: Int
)

internal const val TCP_PEAK_CONSECUTIVE_FAILURE_LIMIT = 200
internal const val TCP_PEAK_RECENT_OUTCOME_WINDOW = 200

internal fun tcpPeakFailureStopReason(
    consecutiveFailures: Int,
    recentOutcomes: Collection<Boolean>,
    noGrowthMs: Long
): String? {
    if (consecutiveFailures >= TCP_PEAK_CONSECUTIVE_FAILURE_LIMIT) {
        return "连续连接失败 200 次，停止新增连接"
    }
    if (
        recentOutcomes.size >= 100 &&
        consecutiveFailures >= 50 &&
        noGrowthMs >= 3_000L
    ) {
        val failures = recentOutcomes.count { !it }
        if (failures * 100 >= recentOutcomes.size * 80) {
            return "连接成功率持续过低，已确认增长平台"
        }
    }
    return null
}

internal fun tcpPeakRemainingFailureBudget(consecutiveFailures: Int, pending: Int): Int =
    (TCP_PEAK_CONSECUTIVE_FAILURE_LIMIT - consecutiveFailures - pending).coerceAtLeast(0)

fun appendTcpPeakTrend(
    current: List<TcpPeakTrendPoint>,
    snapshot: TcpPeakSnapshot,
    epochMs: Long,
    maximumPoints: Int = 300
): List<TcpPeakTrendPoint> {
    val point = TcpPeakTrendPoint(epochMs / 1_000L, snapshot.ipv4.current, snapshot.ipv6.current)
    val next = if (current.lastOrNull()?.epochSecond == point.epochSecond) {
        current.dropLast(1) + point
    } else {
        current + point
    }
    return next.takeLast(maximumPoints.coerceAtLeast(2))
}

data class TcpPeakHistory(
    val id: String,
    val startedEpochMs: Long,
    val side: TcpPeakSide,
    val host: String,
    val port: Int,
    val family: TcpPeakFamily,
    val snapshot: TcpPeakSnapshot
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("startedEpochMs", startedEpochMs)
        .put("side", side.name)
        .put("host", host)
        .put("port", port)
        .put("family", family.name)
        .put("snapshot", snapshot.toHistoryJson())

    companion object {
        fun fromJson(row: JSONObject): TcpPeakHistory? {
            val started = row.optLong("startedEpochMs")
            if (started <= 0L) return null
            return TcpPeakHistory(
                id = row.optString("id").ifBlank { started.toString() },
                startedEpochMs = started,
                side = runCatching { TcpPeakSide.valueOf(row.optString("side")) }.getOrDefault(TcpPeakSide.APP),
                host = row.optString("host"),
                port = row.optInt("port", 443).coerceIn(1, 65_535),
                family = runCatching { TcpPeakFamily.valueOf(row.optString("family")) }.getOrDefault(TcpPeakFamily.BOTH),
                snapshot = TcpPeakSnapshot.fromHistoryJson(row.optJSONObject("snapshot") ?: JSONObject())
            )
        }
    }
}

fun parseTcpPeakHistory(raw: String, nowEpochMs: Long = System.currentTimeMillis()): List<TcpPeakHistory> {
    val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    return (0 until array.length())
        .mapNotNull { array.optJSONObject(it)?.let(TcpPeakHistory::fromJson) }
        .filter { nowEpochMs - it.startedEpochMs in 0..TCP_PEAK_HISTORY_RETENTION_MS }
        .sortedByDescending(TcpPeakHistory::startedEpochMs)
}

fun encodeTcpPeakHistory(values: List<TcpPeakHistory>, nowEpochMs: Long = System.currentTimeMillis()): String {
    val array = JSONArray()
    values
        .filter { nowEpochMs - it.startedEpochMs in 0..TCP_PEAK_HISTORY_RETENTION_MS }
        .sortedByDescending(TcpPeakHistory::startedEpochMs)
        .take(100)
        .forEach { array.put(it.toJson()) }
    return array.toString()
}

fun tcpPeakStateZh(state: String): String = when (state.lowercase(Locale.ROOT)) {
    "queued" -> "等待 Relay 领取任务"
    "accepted" -> "Relay 已领取任务"
    "running" -> "测试中"
    "stop_requested" -> "正在停止"
    "releasing" -> "正在释放连接"
    "completed" -> "测试已完成"
    "stopped" -> "测试已停止"
    "failed" -> "测试失败"
    "interrupted" -> "测试已中断"
    else -> "待测试"
}

private fun TcpPeakSnapshot.toHistoryJson(): JSONObject = JSONObject()
    .put("taskId", taskId)
    .put("side", side.name)
    .put("state", state)
    .put("status", status)
    .put("finishReason", finishReason)
    .put("ipv4", ipv4.toJson())
    .put("ipv6", ipv6.toJson())
    .put("logs", JSONArray(logs))
    .put("conntrackPeak", conntrackPeak)
    .put("cpuPeak", cpuPeak)
    .put("memoryMinAvailableMb", memoryMinAvailableMb)
    .put("resourcesReleased", resourcesReleased)
    .put("releaseStatus", releaseStatus)
    .put("startedEpochMs", startedEpochMs)
    .put("finishedEpochMs", finishedEpochMs)
    .put("error", error)

private fun TcpPeakSnapshot.Companion.fromHistoryJson(row: JSONObject): TcpPeakSnapshot {
    val logs = row.optJSONArray("logs") ?: JSONArray()
    return TcpPeakSnapshot(
        taskId = row.optString("taskId"),
        side = runCatching { TcpPeakSide.valueOf(row.optString("side")) }.getOrDefault(TcpPeakSide.APP),
        state = row.optString("state", "interrupted"),
        status = row.optString("status").ifBlank { "历史记录" },
        finishReason = row.optString("finishReason"),
        ipv4 = TcpPeakMetric.fromJson(row.optJSONObject("ipv4")),
        ipv6 = TcpPeakMetric.fromJson(row.optJSONObject("ipv6")),
        logs = (0 until logs.length()).map { logs.optString(it) }.filter(String::isNotBlank).takeLast(80),
        conntrackPeak = row.optLong("conntrackPeak"),
        cpuPeak = row.optDouble("cpuPeak", 0.0),
        memoryMinAvailableMb = row.optLong("memoryMinAvailableMb"),
        resourcesReleased = row.optBoolean("resourcesReleased", true),
        releaseStatus = row.optString("releaseStatus"),
        startedEpochMs = row.optLong("startedEpochMs"),
        finishedEpochMs = row.optLong("finishedEpochMs"),
        error = row.optString("error")
    )
}

const val TCP_PEAK_HISTORY_RETENTION_MS = 14L * 24L * 60L * 60L * 1_000L
