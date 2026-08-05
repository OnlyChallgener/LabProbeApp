package com.labprobe.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** UI state for Relay version monitoring and update delivery. */
data class AgentUpdateUiState(
    val info: AgentUpdateInfo? = null,
    val message: String = "等待检查 Rust Agent 版本",
    val busy: Boolean = false,
)

/**
 * Application-level coordinator for Relay update work.
 *
 * Version monitoring and update polling must outlive the health-detail
 * Composable. Network calls already switch to Dispatchers.IO in HubApi, while
 * state delivery stays on the application Main scope.
 */
object AgentUpdateCoordinator {
    private const val STATUS_REFRESH_INTERVAL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(AgentUpdateUiState())
    val state: StateFlow<AgentUpdateUiState> = _state.asStateFlow()

    private var boundKey: String = ""
    private var monitorJob: Job? = null

    fun bind(prefs: AppPrefs) {
        val key = "${prefs.hub.trim()}|${prefs.token.hashCode()}"
        if (key == boundKey && monitorJob?.isActive == true) return

        boundKey = key
        monitorJob?.cancel()

        val stored = parseStoredAgentInfo(prefs.agentUpdateInfoJson)
        _state.value = AgentUpdateUiState(
            info = stored,
            message = prefs.agentUpdateMessage.ifBlank { "等待检查 Rust Agent 版本" },
            busy = false,
        )

        if (prefs.hub.isNotBlank() && prefs.token.isNotBlank()) {
            monitorJob = scope.launch {
                while (isActive && key == boundKey) {
                    refreshStatusSilently(prefs)
                    delay(STATUS_REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    fun check(prefs: AppPrefs, silent: Boolean = false) {
        bind(prefs)
        scope.launch {
            operationMutex.withLock {
                if (!hasConnectionSettings(prefs)) return@withLock

                val previous = _state.value
                _state.value = previous.copy(
                    busy = true,
                    message = if (silent) previous.message else "正在检查 Relay 版本…",
                )
                try {
                    val api = HubApi(prefs)
                    api.requestAgentUpdateCheck()
                    val info = pollCheck(api)
                    publish(prefs, normalizeSettledInfo(info))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    publishError(
                        prefs,
                        agentUpdateErrorMessage(
                            raw = error.message,
                            update = false,
                            commandAccepted = false,
                        ),
                    )
                }
            }
        }
    }

    fun update(prefs: AppPrefs) {
        bind(prefs)
        scope.launch {
            operationMutex.withLock {
                if (!hasConnectionSettings(prefs)) return@withLock

                _state.value = _state.value.copy(
                    busy = true,
                    message = "正在向 Relay 下发更新…",
                )
                var commandAccepted = false
                try {
                    val api = HubApi(prefs)
                    api.requestAgentUpdate()
                    commandAccepted = true
                    val result = pollUpdate(api)
                    publish(prefs, result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    publishError(
                        prefs,
                        agentUpdateErrorMessage(
                            raw = error.message,
                            update = true,
                            commandAccepted = commandAccepted,
                        ),
                    )
                }
            }
        }
    }

    private fun hasConnectionSettings(prefs: AppPrefs): Boolean {
        if (prefs.hub.isNotBlank() && prefs.token.isNotBlank()) return true
        publishError(prefs, "请先配置 Hub 地址和 APP Token")
        return false
    }

    private suspend fun refreshStatusSilently(prefs: AppPrefs) {
        if (!hasConnectionSettingsSilently(prefs)) return
        try {
            val info = normalizeSettledInfo(HubApi(prefs).getAgentUpdateStatus())
            val previous = _state.value
            val changed = previous.info?.currentVersion != info.currentVersion ||
                previous.info?.latestVersion != info.latestVersion ||
                previous.info?.lastSeenAt != info.lastSeenAt ||
                previous.info?.state != info.state ||
                previous.info?.updateAvailable != info.updateAvailable
            if (changed && !previous.busy) {
                publish(prefs, info)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Background monitoring is deliberately quiet. Manual check/update
            // actions surface their own user-facing errors and preserve cache.
        }
    }

    private fun hasConnectionSettingsSilently(prefs: AppPrefs): Boolean =
        prefs.hub.isNotBlank() && prefs.token.isNotBlank()

    private suspend fun pollCheck(api: HubApi): AgentUpdateInfo {
        var info = api.getAgentUpdateStatus()
        repeat(15) {
            val checking = info.state.equals("checking", ignoreCase = true) ||
                info.message.contains("后台检查") ||
                info.latestVersion.isUnknownVersion()
            if (!checking) return info
            delay(800L)
            info = api.getAgentUpdateStatus()
        }
        return info
    }

    private suspend fun pollUpdate(api: HubApi): AgentUpdateInfo {
        var info = api.getAgentUpdateStatus()
        repeat(120) {
            completedAgentUpdate(info)?.let { return it }
            if (info.state.equals("failed", ignoreCase = true)) {
                error(info.message.ifBlank { "Relay 更新任务失败" })
            }
            delay(1_000L)
            info = api.getAgentUpdateStatus()
        }
        completedAgentUpdate(info)?.let { return it }
        return info.copy(
            message = "更新指令已下发，正在等待 Relay 重新上报版本",
            state = if (info.state == "failed") "failed" else "waiting_report",
        )
    }

    private fun normalizeSettledInfo(info: AgentUpdateInfo): AgentUpdateInfo {
        return if (agentVersionAtLeast(info.currentVersion, info.latestVersion)) {
            info.copy(
                updateAvailable = false,
                message = "当前已是最新版本 ${info.currentVersion}",
                state = if (info.state == "failed") "idle" else info.state,
            )
        } else {
            info.copy(message = info.message.ifBlank {
                if (info.updateAvailable) {
                    "发现 Relay 新版本 ${info.latestVersion}"
                } else {
                    "Relay 版本状态已刷新"
                }
            })
        }
    }

    private fun publish(prefs: AppPrefs, info: AgentUpdateInfo) {
        val message = info.message.ifBlank { "Relay 版本状态已刷新" }
        _state.value = AgentUpdateUiState(info = info, message = message, busy = false)
        prefs.agentUpdateInfoJson = info.toCoordinatorJson()
        prefs.agentUpdateMessage = message
    }

    private fun publishError(prefs: AppPrefs, message: String) {
        _state.value = _state.value.copy(message = message, busy = false)
        prefs.agentUpdateMessage = message
    }
}

internal fun completedAgentUpdate(info: AgentUpdateInfo): AgentUpdateInfo? {
    if (!agentVersionAtLeast(info.currentVersion, info.latestVersion)) return null
    return info.copy(
        updateAvailable = false,
        state = "completed",
        message = "Relay 已更新到 ${info.currentVersion}",
    )
}

internal fun agentVersionAtLeast(current: String, latest: String): Boolean {
    if (current.isUnknownVersion() || latest.isUnknownVersion()) return false
    val left = versionParts(current)
    val right = versionParts(latest)
    val width = maxOf(left.size, right.size)
    for (index in 0 until width) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a > b
    }
    return true
}

private fun versionParts(value: String): List<Int> = Regex("\\d+")
    .findAll(value)
    .mapNotNull { it.value.toIntOrNull() }
    .toList()

private fun String.isUnknownVersion(): Boolean {
    val value = trim()
    return value.isBlank() || value == "未知" || versionParts(value).isEmpty()
}

private fun AgentUpdateInfo.toCoordinatorJson(): String = JSONObject()
    .put("currentVersion", currentVersion)
    .put("latestVersion", latestVersion)
    .put("updateAvailable", updateAvailable)
    .put("state", state)
    .put("message", message)
    .put("lastSeenAt", lastSeenAt)
    .toString()

private fun parseStoredAgentInfo(raw: String): AgentUpdateInfo? {
    if (raw.isBlank()) return null
    return runCatching {
        val root = JSONObject(raw)
        AgentUpdateInfo(
            currentVersion = root.optString("currentVersion", "未知"),
            latestVersion = root.optString("latestVersion", "未知"),
            updateAvailable = root.optBoolean("updateAvailable", false),
            state = root.optString("state", "idle"),
            message = root.optString("message", ""),
            lastSeenAt = root.optString("lastSeenAt", ""),
        )
    }.getOrNull()
}

internal fun agentUpdateErrorMessage(
    raw: String?,
    update: Boolean,
    commandAccepted: Boolean,
): String {
    val text = raw.orEmpty().trim()
    val lower = text.lowercase()
    val prefix = if (update) "更新下发失败" else "版本检查失败"
    return when {
        text.isBlank() -> "$prefix，已保留上次版本信息"
        "remembercoroutinescope" in lower || "left the composition" in lower ->
            "更新任务已转入后台继续执行"
        "timeout" in lower || "timed out" in lower ->
            when {
                !update -> "版本检查超时，已保留上次结果"
                commandAccepted -> "更新指令已下发，等待 Relay 重新上报"
                else -> "更新请求超时，尚未确认 Hub 已接收指令"
            }
        "502" in lower || "<!doctype" in lower || "<html" in lower ->
            "更新源暂不可用，已保留上次版本信息"
        else -> "$prefix：${text.take(140)}"
    }
}
