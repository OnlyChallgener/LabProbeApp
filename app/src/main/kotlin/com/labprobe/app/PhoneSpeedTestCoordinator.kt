package com.labprobe.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

object PhoneSpeedTestCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PhoneSpeedTestProgress())
    val state: StateFlow<PhoneSpeedTestProgress> = _state.asStateFlow()
    private val generation = AtomicLong(0L)
    private var job: Job? = null

    private fun updateIfCurrent(id: Long, update: () -> Unit) {
        synchronized(this) {
            if (generation.get() == id) update()
        }
    }

    @Synchronized
    fun start(
        context: Context,
        prefs: AppPrefs,
        node: PhoneSpeedTestNode,
        network: PhoneSpeedTestNetworkInfo
    ): Boolean {
        if (job?.isActive == true) return false
        val id = generation.incrementAndGet()
        _state.value = PhoneSpeedTestProgress(phase = "latency", message = "准备手机测速")
        job = scope.launch {
            try {
                val result = PhoneSpeedTestEngine(context.applicationContext).run(node, network) { next ->
                    updateIfCurrent(id) {
                        _state.value = if (next.phase == "upload" && next.down.isEmpty()) {
                            next.copy(
                                down = _state.value.down,
                                currentDown = next.currentDown ?: _state.value.currentDown,
                                avgDown = next.avgDown ?: _state.value.avgDown,
                                peakDown = next.peakDown ?: _state.value.peakDown
                            )
                        } else {
                            next
                        }
                    }
                }
                updateIfCurrent(id) {
                    if (result.downAvgMbps != null || result.upAvgMbps != null) {
                        prefs.addPhoneSpeedHistory(result.toHistory())
                    }
                }
            } catch (cancelled: CancellationException) {
                updateIfCurrent(id) {
                    _state.value = _state.value.copy(phase = "cancelled", message = "测速已取消")
                }
                throw cancelled
            } catch (error: Throwable) {
                updateIfCurrent(id) {
                    _state.value = _state.value.copy(
                        phase = "failed",
                        message = error.message?.takeIf { it.isNotBlank() } ?: "手机测速失败"
                    )
                }
            } finally {
                synchronized(this) {
                    if (generation.get() == id) job = null
                }
            }
        }
        return true
    }

    @Synchronized
    fun cancel() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        _state.value = _state.value.copy(phase = "cancelled", message = "测速已取消")
    }

    @Synchronized
    fun clear() {
        if (job?.isActive != true) _state.value = PhoneSpeedTestProgress()
    }
}