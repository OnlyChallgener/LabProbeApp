package com.labprobe.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.time.Instant
import kotlinx.coroutines.delay

/** One clock for the hero, chart and records sheet; refresh on events and foreground return. */
@Composable
internal fun rememberDevicePresenceNow(device: DeviceItem, events: List<EventItem>): Instant {
    val context = LocalContext.current
    val lifecycle = (context.findActivity() as? LifecycleOwner)?.lifecycle
    var nowEpochMs by remember(device.mac) { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(lifecycle, device.mac) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) nowEpochMs = System.currentTimeMillis()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(device.mac, device.online, device.onlineSince, events) {
        nowEpochMs = System.currentTimeMillis()
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    return remember(nowEpochMs) { Instant.ofEpochMilli(nowEpochMs) }
}
