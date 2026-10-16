package com.labprobe.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundRealtimeRecoveryTest {
    @Test
    fun foregroundResumeStartsRealtimeWhileAnEarlierSnapshotIsStillBlocked() = runBlocking {
        val signals = Channel<Boolean>(Channel.UNLIMITED)
        val firstRefresh = CompletableDeferred<Unit>()
        val unblockRefresh = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        val secondRefresh = CompletableDeferred<Boolean>()
        var starts = 0
        var refreshes = 0
        val worker = launch {
            collectForegroundRecovery(signals, startRealtime = {
                starts++
                if (starts == 2) resumed.complete(Unit)
            }, refresh = { forceFull ->
                refreshes++
                if (refreshes == 1) {
                    firstRefresh.complete(Unit)
                    unblockRefresh.await()
                } else secondRefresh.complete(forceFull)
            }, debounceMs = 1L)
        }
        try {
            signals.send(false)
            withTimeout(2000L) { firstRefresh.await() }
            signals.send(true)
            withTimeout(2000L) { resumed.await() }
            assertEquals(1, refreshes)
            unblockRefresh.complete(Unit)
            assertEquals(true, withTimeout(2000L) { secondRefresh.await() })
        } finally {
            worker.cancelAndJoin()
            signals.close()
        }
    }
}
