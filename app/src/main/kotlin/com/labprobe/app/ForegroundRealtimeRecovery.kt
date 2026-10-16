package com.labprobe.app

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground realtime recovery must not queue behind a slow complete snapshot. */
internal suspend fun collectForegroundRecovery(
    signals: ReceiveChannel<Boolean>,
    startRealtime: suspend () -> Unit,
    refresh: suspend (Boolean) -> Unit,
    debounceMs: Long = 180L,
) = coroutineScope {
    val pending = Channel<Unit>(Channel.CONFLATED)
    val needsFull = AtomicBoolean(false)
    val refreshWorker = launch {
        for (ignored in pending) refresh(needsFull.getAndSet(false))
    }
    try {
        for (first in signals) {
            var forceFull = first
            delay(debounceMs)
            while (true) {
                val next = signals.tryReceive().getOrNull() ?: break
                forceFull = forceFull || next
            }
            startRealtime()
            if (forceFull) needsFull.set(true)
            pending.trySend(Unit)
        }
    } finally {
        pending.close()
        refreshWorker.cancel()
    }
}
