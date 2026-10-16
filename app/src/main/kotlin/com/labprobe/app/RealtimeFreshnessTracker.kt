package com.labprobe.app

/** A displayed sample expires on Android's monotonic clock, independent of Hub wall time. */
internal class RealtimeFreshnessTracker {
    var epoch: Long = 0L
        private set
    var deadlineElapsedMs: Long = 0L
        private set

    /** Replayed or older frames may shorten an existing deadline, but never renew it. */
    fun accept(sampleEpochMs: Long, remainingBudgetMs: Long, nowElapsedMs: Long): Boolean {
        if (sampleEpochMs <= 0L || remainingBudgetMs <= 0L || nowElapsedMs < 0L) return false
        val proposedDeadline = if (remainingBudgetMs > Long.MAX_VALUE - nowElapsedMs) {
            Long.MAX_VALUE
        } else {
            nowElapsedMs + remainingBudgetMs
        }
        if (sampleEpochMs > epoch) {
            epoch = sampleEpochMs
            deadlineElapsedMs = proposedDeadline
        } else {
            // Once a displayed sample has expired, a replay cannot revive it.
            if (!isFresh(nowElapsedMs)) return false
            deadlineElapsedMs = minOf(deadlineElapsedMs, proposedDeadline)
        }
        return isFresh(nowElapsedMs)
    }

    fun isFresh(nowElapsedMs: Long): Boolean =
        epoch > 0L && nowElapsedMs >= 0L && nowElapsedMs < deadlineElapsedMs

    fun remainingMs(nowElapsedMs: Long): Long =
        if (isFresh(nowElapsedMs)) deadlineElapsedMs - nowElapsedMs else 0L

    fun reset() {
        epoch = 0L
        deadlineElapsedMs = 0L
    }
}
