package com.labprobe.app

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** A trickling response must not hold the full-sync mutex indefinitely. */
internal fun boundedSnapshotClient(base: OkHttpClient, deadlineMs: Long = 20_000L): OkHttpClient =
    base.newBuilder()
        .callTimeout(deadlineMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
