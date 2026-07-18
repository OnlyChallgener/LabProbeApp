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
    val availabilityTopic: String = ""
) {
    fun usable(): Boolean = enabled && publicUrl.isNotBlank() && revisionTopic.isNotBlank()

    fun toJson(): String = JSONObject()
        .put("enabled", enabled)
        .put("publicUrl", publicUrl)
        .put("username", username)
        .put("password", password)
        .put("revisionTopic", revisionTopic)
        .put("availabilityTopic", availabilityTopic)
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
                    availabilityTopic = root.optString("availabilityTopic")
                )
            }.getOrDefault(HubMqttConfig())
        }
    }
}

sealed interface HubRealtimeState {
    data object Disabled : HubRealtimeState
    data object Connecting : HubRealtimeState
    data object Connected : HubRealtimeState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : HubRealtimeState
    data class HttpFallback(val reason: String) : HubRealtimeState
}

/** Foreground MQTT supervisor. MQTT only carries retained revision signals; HTTP remains the data source. */
class HubMqttClient(
    private val clientId: String,
    private val onState: (HubRealtimeState) -> Unit,
    private val onRevision: (Long) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MqttAsyncClient? = null
    private var reconnectJob: Job? = null
    @Volatile private var desired = false
    @Volatile private var generation = 0L
    private var activeConfig = HubMqttConfig()

    fun start(config: HubMqttConfig) {
        val normalized = config.copy(publicUrl = normalizeMqttUrl(config.publicUrl))
        if (!normalized.usable()) {
            stop()
            onState(HubRealtimeState.Disabled)
            return
        }
        if (desired && activeConfig == normalized && client?.isConnected == true) return
        stopClient()
        activeConfig = normalized
        desired = true
        generation += 1L
        val run = generation
        onState(HubRealtimeState.Connecting)
        connect(run, fastAttempt = 0, slowRetry = false)
    }

    fun stop() {
        desired = false
        generation += 1L
        reconnectJob?.cancel()
        reconnectJob = null
        stopClient()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun stopClient() {
        val current = client
        client = null
        if (current != null) {
            runCatching { if (current.isConnected) current.disconnectForcibly() }
            runCatching { current.close() }
        }
    }

    private fun connect(run: Long, fastAttempt: Int, slowRetry: Boolean) {
        if (!desired || run != generation) return
        scope.launch {
            val mqtt = runCatching {
                MqttAsyncClient(activeConfig.publicUrl, clientId, MemoryPersistence())
            }.getOrElse {
                scheduleReconnect(run, fastAttempt, slowRetry, it.message ?: "MQTT地址无效")
                return@launch
            }
            client = mqtt
            mqtt.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) = Unit

                override fun connectionLost(cause: Throwable?) {
                    if (client === mqtt) client = null
                    runCatching { mqtt.close() }
                    scheduleReconnect(run, 0, false, cause?.message ?: "连接中断")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != activeConfig.revisionTopic || message == null) return
                    val revision = runCatching {
                        JSONObject(String(message.payload, Charsets.UTF_8)).optLong("revision", 0L)
                    }.getOrDefault(0L)
                    if (revision > 0L) onRevision(revision)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = 8
                keepAliveInterval = 25
                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                if (activeConfig.username.isNotBlank()) userName = activeConfig.username
                if (activeConfig.password.isNotBlank()) password = activeConfig.password.toCharArray()
            }
            val connectListener = object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (!desired || run != generation || client !== mqtt) {
                        runCatching { mqtt.disconnectForcibly() }
                        runCatching { mqtt.close() }
                        return
                    }
                    mqtt.subscribe(activeConfig.revisionTopic, 1, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            if (desired && run == generation) onState(HubRealtimeState.Connected)
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            if (client === mqtt) client = null
                            runCatching { mqtt.disconnectForcibly() }
                            runCatching { mqtt.close() }
                            scheduleReconnect(run, fastAttempt, slowRetry, exception?.message ?: "订阅失败")
                        }
                    })
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (client === mqtt) client = null
                    runCatching { mqtt.close() }
                    scheduleReconnect(run, fastAttempt, slowRetry, exception?.message ?: "连接失败")
                }
            }
            try {
                mqtt.connect(options, null, connectListener)
            } catch (error: Exception) {
                if (client === mqtt) client = null
                runCatching { mqtt.close() }
                scheduleReconnect(run, fastAttempt, slowRetry, error.message ?: "连接失败")
            }
        }
    }

    private fun scheduleReconnect(run: Long, fastAttempt: Int, slowRetry: Boolean, reason: String) {
        if (!desired || run != generation) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (slowRetry || fastAttempt >= FAST_RETRY_COUNT) {
                onState(HubRealtimeState.HttpFallback(reason))
                delay(SLOW_RETRY_MS)
                connect(run, FAST_RETRY_COUNT, slowRetry = true)
                return@launch
            }
            val nextAttempt = fastAttempt + 1
            onState(HubRealtimeState.Reconnecting(nextAttempt, FAST_RETRY_COUNT))
            delay(min(16_000L, 1_000L shl (nextAttempt - 1)))
            connect(run, nextAttempt, slowRetry = false)
        }
    }

    companion object {
        private const val FAST_RETRY_COUNT = 5
        private const val SLOW_RETRY_MS = 60_000L

        fun newClientId(): String = "labprobe-app-" + UUID.randomUUID().toString().replace("-", "").take(20)

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
