package com.labprobe.app

internal enum class SettingsConnectionKind { Hub, Token, Router, Realtime, Notice, Normal }

internal enum class SettingsConnectionTone { Success, Connecting, Warning, Error, Neutral }

internal data class SettingsConnectionStatus(
    val message: String,
    val kind: SettingsConnectionKind,
    val tone: SettingsConnectionTone,
)

internal fun settingsConnectionStatus(
    message: String,
    stateMessage: String,
    realtimeState: HubRealtimeState,
    hubConnected: Boolean,
    mqttConnected: Boolean,
    realtimeDataFresh: Boolean,
    lastRouterRealtimeAt: Long,
    realtimeFallbackActive: Boolean,
): SettingsConnectionStatus {
    if (realtimeState is HubRealtimeState.Connecting) {
        return SettingsConnectionStatus(
            if (hubConnected) "实时链路恢复中，已保留上次数据" else "正在连接 Hub",
            SettingsConnectionKind.Notice,
            SettingsConnectionTone.Connecting,
        )
    }

    val healthy = hubConnected && mqttConnected && realtimeDataFresh
    val realtimeReason = (realtimeState as? HubRealtimeState.Reconnecting)?.reason.orEmpty()
    val failures = listOf(message, stateMessage, realtimeReason)
        .map { connectionFailure(it) }
        .filterNotNull()
        .filterNot { healthy && it.kind != SettingsConnectionKind.Router }
        .distinctBy { it.message }
    val failure = failures.minByOrNull {
        when (it.kind) {
            SettingsConnectionKind.Token -> 0
            SettingsConnectionKind.Router -> 1
            SettingsConnectionKind.Hub -> 2
            else -> 3
        }
    }
    if (failure != null) return failure

    val notice = if (healthy) null else listOf(message, stateMessage)
        .firstOrNull { it.isNotBlank() && connectionFailure(it) == null }
        ?.trim()
    if (!notice.isNullOrBlank()) {
        val connecting = notice.contains("正在", true) || notice.contains("重连", true)
        return SettingsConnectionStatus(
            uiMessageZh(notice),
            SettingsConnectionKind.Notice,
            if (connecting) SettingsConnectionTone.Connecting else SettingsConnectionTone.Warning,
        )
    }
    if (realtimeFallbackActive && realtimeDataFresh) {
        return SettingsConnectionStatus("实时数据由备用链路更新", SettingsConnectionKind.Normal, SettingsConnectionTone.Success)
    }
    if (realtimeDataFresh) {
        return SettingsConnectionStatus("实时同步正常", SettingsConnectionKind.Normal, SettingsConnectionTone.Success)
    }
    if (hubConnected && mqttConnected) {
        val text = if (lastRouterRealtimeAt > 0L) "Hub 已连接，等待路由器最新数据" else "实时链路已连接，等待首帧数据"
        return SettingsConnectionStatus(text, SettingsConnectionKind.Realtime, SettingsConnectionTone.Warning)
    }
    if (hubConnected) {
        return SettingsConnectionStatus("Hub 已连接，实时同步异常", SettingsConnectionKind.Realtime, SettingsConnectionTone.Warning)
    }
    return SettingsConnectionStatus("等待连接 Hub", SettingsConnectionKind.Normal, SettingsConnectionTone.Neutral)
}

private fun connectionFailure(raw: String): SettingsConnectionStatus? {
    val text = raw.trim().removePrefix("原因：").trim()
    if (text.isBlank()) return null
    val lower = text.lowercase()
    val router = "路由器" in text || "router" in lower
    val token = !router && ("token" in lower || "unauthorized" in lower || "forbidden" in lower ||
        "认证" in text || "鉴权" in text || Regex("""\b(?:401|403|1008)\b""").containsMatchIn(lower))
    val hub = !router && !token && ("hub" in lower || "wss" in lower || "websocket" in lower)
    val explicitFailure = listOf("失败", "异常", "错误", "超时", "不可达", "拒绝").any { it in text } ||
        listOf("failed", "failure", "error", "timeout", "refused", "unreachable", "closed", "reset").any { it in lower }
    if (!token && !explicitFailure) return null

    val kind = when {
        token -> SettingsConnectionKind.Token
        router -> SettingsConnectionKind.Router
        hub -> SettingsConnectionKind.Hub
        else -> SettingsConnectionKind.Realtime
    }
    if (kind == SettingsConnectionKind.Token) {
        return SettingsConnectionStatus("Token 认证失败，请检查 APP Token", kind, SettingsConnectionTone.Error)
    }
    if (kind == SettingsConnectionKind.Router) {
        val action = if ("配置" in text || "config" in lower) "读取" else "连接"
        return SettingsConnectionStatus("路由器${action}失败，请检查管理连接", kind, SettingsConnectionTone.Warning)
    }
    if (kind == SettingsConnectionKind.Hub) {
        val detail = when {
            "unknownhost" in lower || "unable to resolve" in lower || "域名解析" in text -> "Hub 域名解析失败"
            "certificate" in lower || "certpath" in lower || "sslhandshake" in lower -> "Hub TLS 证书验证失败"
            "timeout" in lower || "timed out" in lower || "超时" in text -> "Hub 连接超时"
            "connection refused" in lower || "拒绝" in text -> "Hub 拒绝连接"
            "unreachable" in lower || "no route" in lower || "不可达" in text -> "当前网络无法到达 Hub"
            else -> uiMessageZh(text)
        }
        return SettingsConnectionStatus(detail.ifBlank { "Hub 连接失败" }, kind, SettingsConnectionTone.Error)
    }
    return SettingsConnectionStatus(uiMessageZh(text), kind, SettingsConnectionTone.Warning)
}
