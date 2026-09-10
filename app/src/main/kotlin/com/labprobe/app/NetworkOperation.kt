package com.labprobe.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class NetworkOperationState(
    val targetId: String,
    val label: String,
    val running: Boolean,
    val error: String? = null,
    val completedVersion: Long = 0L,
)

class NetworkOperations internal constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val lock = Any()
    private var activeOperationId = 0L
    private var completionVersion = 0L
    private val mutableState = MutableStateFlow<NetworkOperationState?>(null)

    val state: StateFlow<NetworkOperationState?> = mutableState.asStateFlow()

    fun launch(
        targetId: String,
        label: String,
        block: suspend (report: (String) -> Unit) -> Unit,
    ): Boolean {
        val operationId: Long = synchronized(lock) {
            if (mutableState.value?.running == true) return false
            (++activeOperationId).also {
                mutableState.value = NetworkOperationState(
                    targetId = targetId,
                    label = label,
                    running = true,
                    completedVersion = completionVersion,
                )
            }
        }
        scope.launch {
            val report: (String) -> Unit = { nextLabel ->
                synchronized(lock) {
                    val current = mutableState.value
                    if (activeOperationId == operationId && current?.running == true) {
                        mutableState.value = current.copy(label = nextLabel)
                    }
                }
            }
            try {
                block(report)
                finish(operationId, targetId, mutableState.value?.label ?: label, null)
            } catch (error: CancellationException) {
                finish(operationId, targetId, mutableState.value?.label ?: label, "操作已取消")
                throw error
            } catch (error: Throwable) {
                finish(
                    operationId,
                    targetId,
                    mutableState.value?.label ?: label,
                    error.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "操作失败",
                )
            }
        }
        return true
    }

    private fun finish(operationId: Long, targetId: String, label: String, error: String?) {
        synchronized(lock) {
            if (activeOperationId != operationId) return
            completionVersion++
            val terminalLabel = if (label.trimStart().startsWith("正在")) {
                if (error == null) "操作已完成" else "操作失败"
            } else label
            mutableState.value = NetworkOperationState(
                targetId = targetId,
                label = terminalLabel,
                running = false,
                error = error,
                completedVersion = completionVersion,
            )
        }
    }
}

object NetworkOperationRegistry {
    private data class Key(val hub: String, val token: String, val hubDns: String)

    private val operations = ConcurrentHashMap<Key, NetworkOperations>()

    fun get(prefs: AppPrefs): NetworkOperations {
        val key = Key(
            hub = prefs.hub.trim().trimEnd('/').lowercase(),
            token = prefs.token.trim(),
            hubDns = prefs.hubDns.trim().trimEnd('.').lowercase(),
        )
        return operations.getOrPut(key) { NetworkOperations() }
    }
}
