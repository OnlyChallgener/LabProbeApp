package com.labprobe.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import java.security.MessageDigest

private const val EVENT_CHANNEL_ID = "labprobe_events_v1"
private const val EVENT_GROUP_KEY = "labprobe_event_updates"
private const val EVENT_SEEN_PREFS_NAME = "labprobe_event_notifications_v1"
private const val EVENT_SEEN_KEY_PREFIX = "seen_"
internal const val EVENT_SEEN_MAX_KEYS = 2000

internal data class EventNotificationSelection(
    val newEvents: List<EventItem>,
    val keysToPersist: List<String>,
    val shouldNotify: Boolean,
)

/** Device presence is shown on Devices; network and mixed batches remain in the event log. */
internal fun eventNotificationRoute(events: List<EventItem>): String =
    if (events.isNotEmpty() && events.all { it.type == "device_online" || it.type == "device_offline" }) {
        "devices"
    } else {
        "events"
    }

/**
 * Selects one delta and the keys that must be persisted for it. A baseline is deliberately
 * silent, while a restart can safely pass the persisted keys back through this same policy.
 */
internal fun selectEventNotificationBatch(
    events: List<EventItem>,
    alreadySeen: Set<String>,
    silentBaseline: Boolean,
): EventNotificationSelection {
    val distinctEvents = events.distinctBy(::eventNotificationIdentity)
    val newEvents = distinctEvents.filter { eventNotificationIdentity(it) !in alreadySeen }
    return EventNotificationSelection(
        newEvents = newEvents,
        keysToPersist = newEvents.map(::eventNotificationIdentity),
        shouldNotify = newEvents.isNotEmpty() && !silentBaseline,
    )
}

/** Keep insertion order and prune only the oldest keys; initialization never clears this set. */
internal fun appendEventNotificationSeen(
    existing: List<String>,
    additions: List<String>,
    limit: Int = EVENT_SEEN_MAX_KEYS,
): List<String> {
    if (limit <= 0) return emptyList()
    return (existing + additions).filter { it.isNotBlank() }.distinct().takeLast(limit)
}

internal fun eventNotificationScopeDigest(hubIdentity: String): String =
    sha256Hex(hubIdentity.trim().ifBlank { "default-hub" })

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun eventNotificationIdentity(event: EventItem): String = if (event.id > 0) {
    "id:${event.id}"
} else {
    listOf(event.type, event.time, event.title, event.name, event.newValue).joinToString("|")
}

private fun eventNotificationStore(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(EVENT_SEEN_PREFS_NAME, Context.MODE_PRIVATE)

private fun eventSeenPreferenceKey(scopeDigest: String): String = EVENT_SEEN_KEY_PREFIX + scopeDigest

private fun readEventNotificationSeen(store: SharedPreferences, scopeDigest: String): List<String> {
    val array = runCatching {
        JSONArray(store.getString(eventSeenPreferenceKey(scopeDigest), "[]") ?: "[]")
    }.getOrElse { JSONArray() }
    return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
}

private fun claimEventNotificationBatch(
    store: SharedPreferences,
    scopeDigest: String,
    events: List<EventItem>,
    silentBaseline: Boolean,
): EventNotificationSelection? = synchronized(store) {
    val existing = readEventNotificationSeen(store, scopeDigest)
    val selection = selectEventNotificationBatch(events, existing.toSet(), silentBaseline)
    if (selection.keysToPersist.isEmpty()) return@synchronized null
    val bounded = appendEventNotificationSeen(existing, selection.keysToPersist)
    val committed = store.edit()
        .putString(eventSeenPreferenceKey(scopeDigest), JSONArray(bounded).toString())
        .commit()
    if (committed) selection else null
}

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

    fun notifyNewEvents(
        context: Context,
        events: List<EventItem>,
        hubIdentity: String = "",
        silentBaseline: Boolean = false,
    ) {
        if (events.isEmpty()) return
        val scopeDigest = eventNotificationScopeDigest(hubIdentity)
        val store = eventNotificationStore(context)
        // Read, claim, and commit under one lock before posting. This is intentionally
        // at-most-once: a process kill after commit but before notify may lose an alert,
        // but cannot replay a cache window.
        val selection = claimEventNotificationBatch(store, scopeDigest, events, silentBaseline) ?: return
        if (!selection.shouldNotify) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)
        val newEvents = selection.newEvents
        val identities = newEvents.map(::eventNotificationIdentity)
        val summary = newEvents.size > 1
        val eventIdentity = if (summary) {
            "batch:${sha256Hex(identities.joinToString("\u001f"))}"
        } else {
            identities.single()
        }
        val detail = if (summary) {
            buildString {
                append("${newEvents.size} 条新事件")
                newEvents.take(8).forEach { append("\n").append(eventLine(it)) }
                if (newEvents.size > 8) append("\n…还有 ${newEvents.size - 8} 条")
            }
        } else {
            eventLine(newEvents.single())
        }
        val title = if (summary) "${newEvents.size} 条新事件" else eventTitle(newEvents.single())
        val notificationRoute = eventNotificationRoute(newEvents)
        val openEvents = PendingIntent.getActivity(
            context,
            eventNotificationRequestCode(scopeDigest, eventIdentity),
            Intent(context, MainActivity::class.java).apply {
                data = Uri.parse("labprobe://$notificationRoute/${scopeDigest}/${sha256Hex(eventIdentity)}")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_route", notificationRoute)
                putExtra("event_identity", eventIdentity)
                putExtra("event_hub_key", scopeDigest)
                putExtra("event_count", newEvents.size)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_labprobe)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openEvents)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .apply {
                if (summary) setGroup(EVENT_GROUP_KEY).setGroupSummary(true)
            }
            .build()
        NotificationManagerCompat.from(context).notify(
            "labprobe-events-$scopeDigest",
            eventNotificationBatchId(identities),
            notification
        )
    }
}

private fun eventNotificationRequestCode(scopeDigest: String, eventIdentity: String): Int =
    0x41000000 or ((scopeDigest + eventIdentity).hashCode() and 0x000FFFFF)

private fun eventNotificationBatchId(identities: List<String>): Int =
    0x40000000 or (identities.joinToString("\u001f").hashCode() and 0x0FFFFFFF)
