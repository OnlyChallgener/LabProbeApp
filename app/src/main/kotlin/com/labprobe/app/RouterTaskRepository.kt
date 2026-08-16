package com.labprobe.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TASK_POLL_MS = 2_500L

internal data class RouterTaskSnapshot(
    val kind: String,
    val taskId: String = "",
    val state: String = "idle",
    val stage: String = "idle",
    val stageText: String = "尚未开始",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastRouterResponseAt: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val message: String = "",
    val log: List<String> = emptyList(),
    val result: JSONObject = JSONObject()
) {
    val active: Boolean get() = state == "queued" || state == "running"
    val succeeded: Boolean get() = state == "succeeded"
    val failed: Boolean get() = state in setOf("failed", "timed_out", "cancelled")

    companion object {
        fun idle(kind: String) = RouterTaskSnapshot(kind = kind)
    }
}

internal class RouterTaskRepository(private val prefs: AppPrefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = HubApi(prefs)
    private val monitorJobs = ConcurrentHashMap<String, Job>()

    private val _nat = MutableStateFlow(RouterTaskSnapshot.idle("nat"))
    val nat: StateFlow<RouterTaskSnapshot> = _nat.asStateFlow()
    private val _diagnostic = MutableStateFlow(RouterTaskSnapshot.idle("diagnostic"))
    val diagnostic: StateFlow<RouterTaskSnapshot> = _diagnostic.asStateFlow()
    private val _beta = MutableStateFlow(RouterTaskSnapshot.idle("beta"))
    val beta: StateFlow<RouterTaskSnapshot> = _beta.asStateFlow()

    fun ensure(kind: String) {
        scope.launch {
            runCatching { refresh(kind) }
            if (current(kind).active) monitor(kind)
        }
    }

    fun acceptRealtime(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val snapshot = parse(root)
        if (snapshot.kind !in setOf("nat", "diagnostic", "beta")) return
        publish(snapshot)
        if (snapshot.active) monitor(snapshot.kind) else monitorJobs.remove(snapshot.kind)?.cancel()
    }

    fun startNat(host: String, port: Int, interfaceName: String, mode: String) {
        start("nat", JSONObject().put("host", host).put("port", port).put("interface", interfaceName).put("mode", mode))
    }

    fun startDiagnostic() = start("diagnostic", JSONObject())
    fun startBeta() = start("beta", JSONObject())

    private fun start(kind: String, body: JSONObject) {
        if (current(kind).active) return
        publish(current(kind).copy(state = "queued", stage = "queued", stageText = when (kind) {
            "nat" -> "正在提交 NAT 检测任务"
            "diagnostic" -> "正在提交网络自检任务"
            else -> "正在提交版本检测任务"
        }, message = ""))
        scope.launch {
            runCatching {
                val root = api.requestJson("/api/router/tasks/$kind", "POST", body)
                parse(root.optJSONObject("data") ?: JSONObject().put("kind", kind))
            }.onSuccess {
                publish(it)
                monitor(kind)
            }.onFailure {
                publish(current(kind).copy(state = "failed", stage = "submit_failed", stageText = "任务提交失败", message = taskErrorZh(it.message)))
            }
        }
    }

    private suspend fun refresh(kind: String): RouterTaskSnapshot {
        val root = api.requestJson("/api/router/tasks/$kind")
        val snapshot = parse(root.optJSONObject("data") ?: JSONObject().put("kind", kind))
        publish(snapshot)
        return snapshot
    }

    private fun monitor(kind: String) {
        if (monitorJobs[kind]?.isActive == true) return
        monitorJobs[kind] = scope.launch {
            while (true) {
                delay(TASK_POLL_MS)
                val next = runCatching { refresh(kind) }.getOrNull() ?: continue
                if (!next.active) break
            }
            monitorJobs.remove(kind)
        }
    }

    private fun current(kind: String): RouterTaskSnapshot = when (kind) {
        "nat" -> _nat.value
        "diagnostic" -> _diagnostic.value
        else -> _beta.value
    }

    private fun publish(snapshot: RouterTaskSnapshot) {
        when (snapshot.kind) {
            "nat" -> _nat.value = snapshot
            "diagnostic" -> _diagnostic.value = snapshot
            "beta" -> _beta.value = snapshot
        }
    }

    private fun parse(root: JSONObject): RouterTaskSnapshot {
        val kind = root.optString("kind").ifBlank { "unknown" }
        val logArray = root.optJSONArray("log") ?: JSONArray()
        val lines = (0 until logArray.length()).mapNotNull { index -> logArray.optString(index).trim().takeIf(String::isNotBlank) }
        return RouterTaskSnapshot(
            kind = kind,
            taskId = root.optString("taskId"),
            state = root.optString("state", "idle"),
            stage = root.optString("stage", "idle"),
            stageText = root.optString("stageText", "尚未开始"),
            startedAt = root.optLong("startedAt", 0L),
            updatedAt = root.optLong("updatedAt", 0L),
            lastRouterResponseAt = root.optLong("lastRouterResponseAt", 0L),
            elapsedSeconds = root.optLong("elapsedSeconds", 0L),
            message = taskMessageZh(root.optString("message")),
            log = lines,
            result = root.optJSONObject("result") ?: JSONObject()
        )
    }
}

internal object RouterTaskRepositoryRegistry {
    private val repositories = ConcurrentHashMap<String, RouterTaskRepository>()
    fun get(prefs: AppPrefs): RouterTaskRepository {
        val key = listOf(prefs.hub.trim(), prefs.token.trim(), prefs.hubDns.trim()).joinToString("|")
        return repositories.getOrPut(key) { RouterTaskRepository(prefs) }
    }
}

internal fun taskMessageZh(raw: String?): String {
    val text = raw.orEmpty().trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> ""
        "no new version" in lower || "no update" in lower || "latest version" in lower -> "暂无可用的新版本"
        "new version" in lower || "update available" in lower -> "发现可用的新版本"
        "timeout" in lower || "timed out" in lower -> "任务执行超时"
        "failed" in lower || "error" in lower -> "任务执行失败"
        else -> text
    }
}

internal fun taskErrorZh(raw: String?): String {
    val text = raw.orEmpty().trim()
    val lower = text.lowercase()
    return when {
        text.isBlank() -> "任务请求失败"
        "timeout" in lower || "timed out" in lower -> "任务请求超时，Hub 将保留现有状态"
        "unauthorized" in lower || "forbidden" in lower -> "Hub 认证失败"
        "failed to connect" in lower || "connection refused" in lower -> "暂时无法连接 Hub"
        else -> taskMessageZh(text)
    }
}
