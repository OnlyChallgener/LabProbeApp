package com.labprobe.app

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

sealed interface HubRealtimeState {
    data object Disabled : HubRealtimeState
    data object Connecting : HubRealtimeState
    data object Connected : HubRealtimeState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int, val reason: String) : HubRealtimeState
}

/**
 * Foreground Hub-native WebSocket client.
 *
 * One authenticated WSS connection carries router fast and terminal deltas.
 * OkHttp protocol pings, Hub application keepalives and a local frame watchdog
 * jointly prevent half-open connections from sitting idle for tens of seconds.
 * HTTP remains calibration-only and is never an automatic realtime fallback.
 */
class HubRealtimeWebSocketClient(
    private val dnsProvider: () -> Dns,
    private val onState: (HubRealtimeState) -> Unit,
    private val onRouterRealtime: (String) -> Unit = {},
    private val onDevicesRealtime: (String) -> Unit = {},
    private val onRealtimeReady: (Boolean) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var httpClient: OkHttpClient? = null
    @Volatile private var desired = false
    @Volatile private var generation = 0L
    @Volatile private var activeUrl = ""
    @Volatile private var activeToken = ""
    @Volatile private var hasConnectedBefore = false
    @Volatile private var lastFrameAt = 0L
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    fun start(hubAddress: String, appToken: String) {
        val url = realtimeUrl(hubAddress)
        val token = appToken.trim()
        if (url.isBlank() || token.isBlank()) {
            stop()
            onState(HubRealtimeState.Disabled)
            return
        }
        if (desired && activeUrl == url && activeToken == token && socket != null) return

        generation += 1L
        val run = generation
        stopActiveSocket()
        desired = true
        activeUrl = url
        activeToken = token
        onState(HubRealtimeState.Connecting)
        connect(run, attempt = 0)
    }

    fun stop() {
        desired = false
        generation += 1L
        reconnectJob?.cancel()
        reconnectJob = null
        hasConnectedBefore = false
        lastFrameAt = 0L
        activeUrl = ""
        activeToken = ""
        stopActiveSocket()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun stopActiveSocket() {
        watchdogJob?.cancel()
        watchdogJob = null
        val currentSocket = socket
        socket = null
        runCatching { currentSocket?.close(1000, "foreground stopped") }
        val currentClient = httpClient
        httpClient = null
        shutdown(currentClient)
    }

    private fun shutdown(client: OkHttpClient?) {
        if (client == null) return
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun connect(run: Long, attempt: Int) {
        if (!desired || run != generation) return
        scope.launch {
            val targetUrl = activeUrl
            val token = activeToken
            val transport = runCatching {
                OkHttpClient.Builder()
                    .dns(dnsProvider())
                    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            }.getOrElse {
                scheduleReconnect(run, attempt, failureReason(it, "WSS 客户端初始化失败"))
                return@launch
            }
            if (!desired || run != generation) {
                shutdown(transport)
                return@launch
            }
            httpClient = transport
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", "Bearer $token")
                .header("X-LabProbe-Token", token)
                .header("Accept", "application/json")
                .build()
            var opened = false
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!desired || run != generation) {
                        webSocket.close(1000, "superseded")
                        return
                    }
                    opened = true
                    socket = webSocket
                    lastFrameAt = SystemClock.elapsedRealtime()
                    startFrameWatchdog(run, webSocket)
                    val reconnect = hasConnectedBefore
                    hasConnectedBefore = true
                    onState(HubRealtimeState.Connected)
                    onRealtimeReady(reconnect)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!desired || run != generation) return
                    lastFrameAt = SystemClock.elapsedRealtime()
                    val root = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val type = root.optString("type")
                    val data = root.optJSONObject("data")
                    when (type) {
                        "router" -> if (data != null) onRouterRealtime(data.toString())
                        "devices" -> if (data != null) onDevicesRealtime(data.toString())
                        "ready", "keepalive" -> Unit
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    release(webSocket, transport)
                    scheduleReconnect(run, if (opened) 0 else attempt, closeReason(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                    release(webSocket, transport)
                    scheduleReconnect(run, if (opened) 0 else attempt, failureReason(throwable, "WSS 连接中断"))
                }
            }
            val created = transport.newWebSocket(request, listener)
            if (!desired || run != generation) {
                created.close(1000, "superseded")
                shutdown(transport)
                return@launch
            }
            if (socket == null) socket = created
        }
    }

    private fun startFrameWatchdog(run: Long, webSocket: WebSocket) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (desired && run == generation && socket === webSocket) {
                delay(WATCHDOG_INTERVAL_MS)
                val last = lastFrameAt
                if (last > 0L && SystemClock.elapsedRealtime() - last >= SERVER_FRAME_TIMEOUT_MS) {
                    webSocket.cancel()
                    return@launch
                }
            }
        }
    }

    private fun release(webSocket: WebSocket, transport: OkHttpClient) {
        if (socket === webSocket) {
            socket = null
            watchdogJob?.cancel()
            watchdogJob = null
        }
        if (httpClient === transport) httpClient = null
        shutdown(transport)
    }

    private fun scheduleReconnect(run: Long, attempt: Int, reason: String) {
        if (!desired || run != generation) return
        val nextAttempt = min(attempt + 1, MAX_RETRY_ATTEMPT)
        onState(HubRealtimeState.Reconnecting(nextAttempt, MAX_RETRY_ATTEMPT, reason))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(retryDelayMs(nextAttempt))
            if (desired && run == generation) connect(run, nextAttempt)
        }
    }

    private fun realtimeUrl(rawHub: String): String {
        val base = normalizeHubBaseUrl(rawHub)
        if (base.isBlank()) return ""
        return when {
            base.startsWith("https://", true) -> "wss://${base.substring(8)}$REALTIME_PATH"
            base.startsWith("http://", true) -> "ws://${base.substring(7)}$REALTIME_PATH"
            else -> ""
        }
    }

    private fun retryDelayMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        else -> 3_000L
    }

    private fun failureReason(error: Throwable?, fallback: String): String =
        error?.message?.trim().takeUnless { it.isNullOrBlank() }?.take(140) ?: fallback

    private fun closeReason(code: Int, reason: String): String {
        val detail = reason.trim().take(100)
        return if (detail.isBlank()) "WSS 已关闭 ($code)" else "WSS 已关闭 ($code): $detail"
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 6L
        const val PING_INTERVAL_SECONDS = 8L
        const val WATCHDOG_INTERVAL_MS = 1_000L
        const val SERVER_FRAME_TIMEOUT_MS = 8_000L
        const val MAX_RETRY_ATTEMPT = 3
        const val REALTIME_PATH = "/api/realtime/ws"
    }
}
