package com.labprobe.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val EVENT_CHANNEL_ID = "labprobe_events_v1"
private const val EVENT_GROUP_KEY = "labprobe_event_updates"

object EventNotificationCenter {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            EVENT_CHANNEL_ID,
            "事件记录",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "设备上线、离线以及网络地址变化提醒"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyNewEvents(context: Context, events: List<EventItem>) {
        if (events.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        val openApp = PendingIntent.getActivity(
            context,
            4100,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        events.take(6).reversed().forEach { event ->
            val detail = eventLine(event)
            val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_labprobe)
                .setContentTitle(eventTitle(event))
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(EVENT_GROUP_KEY)
                .setOnlyAlertOnce(false)
                .build()
            manager.notify(eventNotificationId(event), notification)
        }
    }
}

fun eventNotificationIdentity(event: EventItem): String = if (event.id > 0) {
    "id:${event.id}"
} else {
    listOf(event.type, event.time, event.title, event.name, event.newValue).joinToString("|")
}

private fun eventNotificationId(event: EventItem): Int {
    val stable = eventNotificationIdentity(event).hashCode() and 0x0FFFFFFF
    return 0x40000000 or stable
}
