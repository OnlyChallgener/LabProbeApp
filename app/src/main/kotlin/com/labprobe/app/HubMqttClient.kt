package com.labprobe.app

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
 * The handshake is authenticated by the existing APP_TOKEN.  There is no MQTT
 * broker URL, account, password, topic subscription, or automatic HTTP
 * fallback in this realtime path.
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
    private var reconnectJob: Job? = null

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
        activeUrl = ""
        activeToken = ""
        stopActiveSocket()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun stopActiveSocket() {
        val currentSocket = socket
        socket = null
        runCatching { currentSocket?.close(1000, "foreground stopped") }
        val currentClient = httpClient
        httpClient = null
        runCatching { currentClient?.dispatcher?.executorService?.shutdown() }
        runCatching { currentClient?.connectionPool?.evictAll() }
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
                transport.dispatcher.executorService.shutdown()
                return@launch
            }
            httpClient = transport
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", "Bearer $token")
                .header("X-LabProbe-Token", token)
                .header("Accept", "application/json")
                .build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!desired || run != generation) {
                        webSocket.close(1000, "superseded")
                        return
                    }
                    val reconnect = hasConnectedBefore
                    hasConnectedBefore = true
                    onState(HubRealtimeState.Connected)
                    onRealtimeReady(reconnect)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!desired || run != generation) return
                    val root = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val data = root.optJSONObject("data") ?: return
                    when (root.optString("type")) {
                        "router" -> onRouterRealtime(data.toString())
                        "devices" -> onDevicesRealtime(data.toString())
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    release(webSocket)
                    scheduleReconnect(run, attempt, closeReason(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                    release(webSocket)
                    scheduleReconnect(run, attempt, failureReason(throwable, "WSS 连接中断"))
                }
            }
            val created = transport.newWebSocket(request, listener)
            if (!desired || run != generation) {
                created.close(1000, "superseded")
                return@launch
            }
            socket = created
        }
    }

    private fun release(webSocket: WebSocket) {
        if (socket === webSocket) socket = null
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
            base.startsWith("https://", true) -> "wss://${base.substring(8)}/api/realtime/ws"
            base.startsWith("http://", true) -> "ws://${base.substring(7)}/api/realtime/ws"
            else -> ""
        }
    }

    private fun retryDelayMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 8_000L
        else -> 15_000L
    }

    private fun failureReason(error: Throwable?, fallback: String): String =
        error?.message?.trim().takeUnless { it.isNullOrBlank() }?.take(140) ?: fallback

    private fun closeReason(code: Int, reason: String): String {
        val detail = reason.trim().take(100)
        return if (detail.isBlank()) "WSS 已关闭 ($code)" else "WSS 已关闭 ($code): $detail"
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val PING_INTERVAL_SECONDS = 15L
        const val MAX_RETRY_ATTEMPT = 5
    }
}
