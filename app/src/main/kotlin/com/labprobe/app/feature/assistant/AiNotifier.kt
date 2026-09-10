package com.labprobe.app.feature.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.labprobe.app.MainActivity
import com.labprobe.app.R
import java.security.MessageDigest

private const val AI_NOTICE_INLINE_BYTES = 24 * 1024
private const val AI_NOTICE_TITLE_INLINE_BYTES = 4 * 1024
private const val AI_NOTICE_PREVIEW_TITLE_CHARS = 240
private const val AI_NOTICE_PREVIEW_CHARS = 4_000
private const val AI_NOTICE_STORE = "ai_notification_payloads_v1"
private const val AI_NOTICE_PAYLOAD_PREFIX = "payload:"
private const val AI_NOTICE_TIME_PREFIX = "time:"
private const val AI_NOTICE_REFERENCE_PREFIX = "labprobe-ai-notice-ref:"
// Keep enough referenced fields for a full notification tray plus delayed taps.
private const val AI_NOTICE_STORE_LIMIT = 100

internal data class AiNotificationDeliveryIdentity(
    val noticeId: String,
    val hubKey: String,
    val notificationTag: String,
    val managerId: Int,
    val requestCode: Int,
    val intentData: Uri,
)

internal fun aiNotificationDeliveryIdentity(
    notificationId: Int,
    title: String,
    content: String,
    hubIdentity: String,
): AiNotificationDeliveryIdentity {
    val hubKey = aiNotificationHubKey(hubIdentity)
    val noticeId = if (notificationId != 0) notificationId.toString() else {
        MessageDigest.getInstance("SHA-256")
            .digest("$title\u0000$content".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(24)
    }
    val stableKey = "${hubKey.ifBlank { "unknown" }}:$noticeId"
    val stableInt = (stableKey.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    return AiNotificationDeliveryIdentity(
        noticeId = noticeId,
        hubKey = hubKey,
        notificationTag = "labprobe.ai.notice.$stableKey",
        managerId = if (notificationId != 0) (notificationId and Int.MAX_VALUE).coerceAtLeast(1) else stableInt,
        requestCode = stableInt,
        intentData = Uri.Builder()
            .scheme("labprobe")
            .authority("ai-notice")
            .appendPath(hubKey.ifBlank { "unknown" })
            .appendPath(noticeId)
            .build(),
    )
}

internal fun aiNotificationIntent(
    context: Context,
    route: String,
    title: String,
    content: String,
    identity: AiNotificationDeliveryIdentity,
): Intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    data = identity.intentData
    putExtra("navigate_route", route)
    putExtra("ai_notice_id", identity.noticeId)
    putExtra("ai_notice_title", title)
    putExtra("ai_notice_content", content)
    putExtra("ai_notice_hub_key", identity.hubKey)
}

internal fun aiNotificationIntentContent(
    context: Context,
    identity: AiNotificationDeliveryIdentity,
    content: String,
): String = aiNotificationIntentText(context, identity, "content", content, AI_NOTICE_INLINE_BYTES)

internal fun aiNotificationIntentTitle(
    context: Context,
    identity: AiNotificationDeliveryIdentity,
    title: String,
): String = aiNotificationIntentText(context, identity, "title", title, AI_NOTICE_TITLE_INLINE_BYTES)

private fun aiNotificationIntentText(
    context: Context,
    identity: AiNotificationDeliveryIdentity,
    field: String,
    value: String,
    inlineBytes: Int,
): String {
    if (value.toByteArray(Charsets.UTF_8).size <= inlineBytes && !value.startsWith(AI_NOTICE_REFERENCE_PREFIX)) return value
    val storeKey = MessageDigest.getInstance("SHA-256")
        .digest("${identity.hubKey}\u0000${identity.noticeId}\u0000$field".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    val prefs = context.getSharedPreferences(AI_NOTICE_STORE, Context.MODE_PRIVATE)
    val staleKeys = prefs.all.asSequence()
        .filter { (key, _) -> key.startsWith(AI_NOTICE_TIME_PREFIX) && key != "$AI_NOTICE_TIME_PREFIX$storeKey" }
        .sortedBy { (_, value) -> value as? Long ?: 0L }
        .map { (key, _) -> key.removePrefix(AI_NOTICE_TIME_PREFIX) }
        .toList()
        .take((prefs.all.keys.count { it.startsWith(AI_NOTICE_TIME_PREFIX) } - AI_NOTICE_STORE_LIMIT + 1).coerceAtLeast(0))
    val editor = prefs.edit()
    staleKeys.forEach { stale ->
        editor.remove("$AI_NOTICE_PAYLOAD_PREFIX$stale")
        editor.remove("$AI_NOTICE_TIME_PREFIX$stale")
    }
    check(
        editor
            .putString("$AI_NOTICE_PAYLOAD_PREFIX$storeKey", value)
            .putLong("$AI_NOTICE_TIME_PREFIX$storeKey", System.currentTimeMillis())
            .commit()
    ) { "AI 通知正文无法安全保存" }
    return "$AI_NOTICE_REFERENCE_PREFIX$storeKey"
}

internal fun aiNotificationResolveContent(context: Context, payload: String): String {
    if (!payload.startsWith(AI_NOTICE_REFERENCE_PREFIX)) return payload
    val storeKey = payload.removePrefix(AI_NOTICE_REFERENCE_PREFIX)
    return context.getSharedPreferences(AI_NOTICE_STORE, Context.MODE_PRIVATE)
        .getString("$AI_NOTICE_PAYLOAD_PREFIX$storeKey", null)
        ?: "这条通知的本地内容已过期或不可用，无法展示原文。"
}

internal fun aiNotificationContentIsStored(payload: String): Boolean =
    payload.startsWith(AI_NOTICE_REFERENCE_PREFIX)

/** System notifications for assistant messages, independent from chat history. */
object AiNotifier {
    private const val CHANNEL_ID = "labprobe_ai_assistant"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AI 助手", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "AI 助手在后台收到的消息与提醒"
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** Returns true only after Android accepted the notification for delivery. */
    fun notifyAssistantMessage(
        context: Context,
        title: String,
        content: String,
        route: String = "ai_chat",
        notificationId: Int = 0,
        hubIdentity: String = "",
    ): Boolean {
        return try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            val identity = aiNotificationDeliveryIdentity(notificationId, title, content, hubIdentity)
            val intentTitle = aiNotificationIntentTitle(context, identity, title)
            val intentContent = aiNotificationIntentContent(context, identity, content)
            val previewTitle = if (title.length > AI_NOTICE_PREVIEW_TITLE_CHARS) {
                title.take(AI_NOTICE_PREVIEW_TITLE_CHARS) + "…"
            } else title
            val preview = if (aiNotificationContentIsStored(intentContent)) {
                content.take(AI_NOTICE_PREVIEW_CHARS) + "…（打开查看完整内容）"
            } else content
            val openApp = PendingIntent.getActivity(
                context,
                identity.requestCode,
                aiNotificationIntent(context, route, intentTitle, intentContent, identity),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_labprobe)
                .setContentTitle(previewTitle)
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(identity.notificationTag, identity.managerId, notification)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
