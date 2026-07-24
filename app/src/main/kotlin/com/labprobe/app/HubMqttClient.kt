package com.labprobe.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

data class HubMqttConfig(
    val enabled: Boolean = false,
    val publicUrl: String = "",
    val username: String = "",
    val password: String = "",
    val revisionTopic: String = "",
    val availabilityTopic: String = "",
    val dashboardTopic: String = "",
    val routerRealtimeTopic: String = "",
    val devicesRealtimeTopic: String = "",
    val realtimeDemandTopic: String = "",
) {
    fun usable(): Boolean = enabled && publicUrl.isNotBlank() &&
        routerRealtimeTopic.isNotBlank() && devicesRealtimeTopic.isNotBlank() &&
        realtimeDemandTopic.isNotBlank()

    fun toJson(): String = JSONObject()
        .put("enabled", enabled)
        .put("publicUrl", publicUrl)
        .put("username", username)
        .put("password", password)
        .put("revisionTopic", revisionTopic)
        .put("availabilityTopic", availabilityTopic)
        .put("dashboardTopic", dashboardTopic)
        .put("routerRealtimeTopic", routerRealtimeTopic)
        .put("devicesRealtimeTopic", devicesRealtimeTopic)
        .put("realtimeDemandTopic", realtimeDemandTopic)
        .toString()

    companion object {
        fun fromJson(raw: String): HubMqttConfig {
            if (raw.isBlank()) return HubMqttConfig()
            return runCatching {
                val root = JSONObject(raw)
                HubMqttConfig(
                    enabled = root.optBoolean("enabled", false),
                    publicUrl = root.optString("publicUrl"),
                    username = root.optString("username"),
                    password = root.optString("password"),
                    revisionTopic = root.optString("revisionTopic"),
                    availabilityTopic = root.optString("availabilityTopic"),
                    dashboardTopic = root.optString("dashboardTopic"),
                    routerRealtimeTopic = root.optString("routerRealtimeTopic"),
                    devicesRealtimeTopic = root.optString("devicesRealtimeTopic"),
                    realtimeDemandTopic = root.optString("realtimeDemandTopic"),
                )
            }.getOrDefault(HubMqttConfig())
        }
    }
}

sealed interface HubRealtimeState {
    data object Disabled : HubRealtimeState
    data object Connecting : HubRealtimeState
    data object Connected : HubRealtimeState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int, val reason: String) : HubRealtimeState
    /** Source-compatibility only. The WSS supervisor never emits this state. */
    data class HttpFallback(val reason: String) : HubRealtimeState
}

/** Foreground WSS supervisor for sync signals and compact realtime deltas. */
class HubMqttClient(
    private val clientId: String,
    private val onState: (HubRealtimeState) -> Unit,
    private val onRevision: (Long) -> Unit,
    private val onRouterRealtime: (String) -> Unit = {},
    private val onDevicesRealtime: (String) -> Unit = {},
    private val onRealtimeReady: (Boolean) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var client: MqttAsyncClient? = null
    private var reconnectJob: Job? = null
    private var demandHeartbeatJob: Job? = null
    @Volatile private var desired = false
    @Volatile private var generation = 0L
    @Volatile private var activeConfig = HubMqttConfig()
    @Volatile private var activeDemandTopic = ""
    @Volatile private var hasConnectedBefore = false

    fun start(config: HubMqttConfig) {
        val normalized = config.copy(publicUrl = normalizeMqttUrl(config.publicUrl))
        if (!normalized.usable()) {
            stop()
            onState(HubRealtimeState.Disabled)
            return
        }
        if (desired && activeConfig == normalized && client?.isConnected == true) return
        generation += 1L
        val run = generation
        stopClient()
        activeConfig = normalized
        desired = true
        onState(HubRealtimeState.Connecting)
        connect(run, fastAttempt = 0, slowRetry = false)
    }

    fun stop() {
        desired = false
        generation += 1L
        reconnectJob?.cancel()
        reconnectJob = null
        demandHeartbeatJob?.cancel()
        demandHeartbeatJob = null
        hasConnectedBefore = false
        stopClient()
    }

    fun close() {
        desired = false
        generation += 1L
        reconnectJob?.cancel()
        reconnectJob = null
        demandHeartbeatJob?.cancel()
        demandHeartbeatJob = null
        hasConnectedBefore = false
        val current = detachClient()
        val demandTopic = activeDemandTopic
        activeDemandTopic = ""
        scope.launch {
            teardownClient(current, announceOffline = true, demandTopic = demandTopic)
            scope.cancel()
        }
    }

    private fun stopClient() {
        demandHeartbeatJob?.cancel()
        demandHeartbeatJob = null
        val current = detachClient()
        val demandTopic = activeDemandTopic
        activeDemandTopic = ""
        if (current != null) {
            scope.launch { teardownClient(current, announceOffline = true, demandTopic = demandTopic) }
        }
    }

    private fun detachClient(): MqttAsyncClient? {
        val current = client
        client = null
        return current
    }

    private fun demandClientTopic(config: HubMqttConfig, run: Long): String {
        val base = config.realtimeDemandTopic.trimEnd('/')
        return if (base.isBlank()) "" else "$base/$clientId-$run"
    }

    private fun teardownClient(
        current: MqttAsyncClient?,
        announceOffline: Boolean,
        demandTopic: String,
    ) {
        if (current == null) return
        if (announceOffline && current.isConnected && demandTopic.isNotBlank()) {
            runCatching {
                current.publish(demandTopic, "offline".toByteArray(), 1, false)
                    .waitForCompletion(1_500L)
            }
        }
        runCatching { if (current.isConnected) current.disconnectForcibly() }
        runCatching { current.close() }
    }

    private fun publishDemand(mqtt: MqttAsyncClient, demandTopic: String) {
        if (!desired || !mqtt.isConnected || demandTopic.isBlank()) return
        runCatching { mqtt.publish(demandTopic, "online".toByteArray(), 1, false) }
    }

    private fun startDemandHeartbeat(run: Long, mqtt: MqttAsyncClient, demandTopic: String) {
        demandHeartbeatJob?.cancel()
        demandHeartbeatJob = scope.launch {
            while (desired && run == generation && client === mqtt && mqtt.isConnected) {
                publishDemand(mqtt, demandTopic)
                delay(DEMAND_HEARTBEAT_MS)
            }
        }
    }

    private fun connect(run: Long, fastAttempt: Int, slowRetry: Boolean) {
        if (!desired || run != generation) return
        scope.launch {
            val config = activeConfig
            val demandTopic = demandClientTopic(config, run)
            val mqtt = runCatching {
                MqttAsyncClient(config.publicUrl, clientId, MemoryPersistence())
            }.getOrElse {
                scheduleReconnect(run, fastAttempt, slowRetry, failureReason(it, "WSS 地址无效"))
                return@launch
            }
            client = mqtt
            activeDemandTopic = demandTopic
            mqtt.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) = Unit

                override fun connectionLost(cause: Throwable?) {
                    if (client === mqtt) {
                        demandHeartbeatJob?.cancel()
                        demandHeartbeatJob = null
                        client = null
                        activeDemandTopic = ""
                    }
                    runCatching { mqtt.close() }
                    scheduleReconnect(run, 0, false, failureReason(cause, "WSS 连接中断"))
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (message == null) return
                    when (topic) {
                        config.revisionTopic -> {
                            val revision = runCatching {
                                JSONObject(String(message.payload, Charsets.UTF_8)).optLong("revision", 0L)
                            }.getOrDefault(0L)
                            if (revision > 0L) onRevision(revision)
                        }
                        config.availabilityTopic -> {
                            when (String(message.payload, Charsets.UTF_8).trim().lowercase()) {
                                "online" -> {
                                    publishDemand(mqtt, demandTopic)
                                    onState(HubRealtimeState.Connected)
                                }
                                "offline" -> onState(
                                    HubRealtimeState.Reconnecting(
                                        FAST_RETRY_COUNT,
                                        FAST_RETRY_COUNT,
                                        "Hub WSS 推送端离线",
                                    )
                                )
                            }
                        }
                        config.routerRealtimeTopic ->
                            onRouterRealtime(String(message.payload, Charsets.UTF_8))
                        config.devicesRealtimeTopic ->
                            onDevicesRealtime(String(message.payload, Charsets.UTF_8))
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = 8
                keepAliveInterval = 25
                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                setWill(demandTopic, "offline".toByteArray(), 1, false)
                if (config.username.isNotBlank()) userName = config.username
                if (config.password.isNotBlank()) password = config.password.toCharArray()
            }
            val connectListener = object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (!desired || run != generation || client !== mqtt) {
                        runCatching { mqtt.disconnectForcibly() }
                        runCatching { mqtt.close() }
                        return
                    }
                    val topicQos = listOf(
                        config.revisionTopic to 1,
                        config.availabilityTopic to 1,
                        config.routerRealtimeTopic to 0,
                        config.devicesRealtimeTopic to 0,
                    ).filter { it.first.isNotBlank() }.distinctBy { it.first }
                    val topics = topicQos.map { it.first }
                    val qoses = topicQos.map { it.second }.toIntArray()
                    mqtt.subscribe(topics.toTypedArray(), qoses, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            if (desired && run == generation) {
                                publishDemand(mqtt, demandTopic)
                                startDemandHeartbeat(run, mqtt, demandTopic)
                                val reconnect = hasConnectedBefore
                                hasConnectedBefore = true
                                onState(HubRealtimeState.Connected)
                                onRealtimeReady(reconnect)
                            }
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            if (client === mqtt) client = null
                            runCatching { mqtt.disconnectForcibly() }
                            runCatching { mqtt.close() }
                            scheduleReconnect(run, fastAttempt, slowRetry, failureReason(exception, "WSS 订阅失败"))
                        }
                    })
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (client === mqtt) client = null
                    runCatching { mqtt.close() }
                    scheduleReconnect(run, fastAttempt, slowRetry, failureReason(exception, "WSS 连接失败"))
                }
            }
            try {
                mqtt.connect(options, null, connectListener)
            } catch (error: Exception) {
                if (client === mqtt) client = null
                runCatching { mqtt.close() }
                scheduleReconnect(run, fastAttempt, slowRetry, failureReason(error, "WSS 连接失败"))
            }
        }
    }

    private fun scheduleReconnect(run: Long, fastAttempt: Int, slowRetry: Boolean, reason: String) {
        if (!desired || run != generation) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (slowRetry || fastAttempt >= FAST_RETRY_COUNT) {
                onState(HubRealtimeState.Reconnecting(FAST_RETRY_COUNT, FAST_RETRY_COUNT, reason))
                delay(SLOW_RETRY_MS)
                connect(run, FAST_RETRY_COUNT, slowRetry = true)
                return@launch
            }
            val nextAttempt = fastAttempt + 1
            onState(HubRealtimeState.Reconnecting(nextAttempt, FAST_RETRY_COUNT, reason))
            delay(min(16_000L, 1_000L shl (nextAttempt - 1)))
            connect(run, nextAttempt, slowRetry = false)
        }
    }

    private fun failureReason(error: Throwable?, fallback: String): String {
        if (error == null) return fallback
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && parts.size < 3) {
            if (current is MqttException) parts += "WSS 错误 ${current.reasonCode}"
            current.message?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }?.let { parts += it }
            current = current.cause
        }
        return parts.distinct().joinToString(" · ").ifBlank { fallback }.take(180)
    }

    companion object {
        private const val FAST_RETRY_COUNT = 5
        private const val SLOW_RETRY_MS = 60_000L
        private const val DEMAND_HEARTBEAT_MS = 15_000L

        fun newClientId(): String =
            "labprobe-app-" + UUID.randomUUID().toString().replace("-", "").take(20)

        private fun normalizeMqttUrl(raw: String): String {
            val clean = raw.trim().trimEnd('/')
            if (clean.isBlank()) return ""
            return when {
                clean.startsWith("wss://", true) || clean.startsWith("ws://", true) ||
                    clean.startsWith("ssl://", true) || clean.startsWith("tcp://", true) -> clean
                else -> "wss://$clean/mqtt"
            }
        }
    }
}
