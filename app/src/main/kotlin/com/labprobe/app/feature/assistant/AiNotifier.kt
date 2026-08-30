package com.labprobe.app.feature.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.labprobe.app.MainActivity
import com.labprobe.app.R

/** System notifications for assistant messages, independent from chat history. */
object AiNotifier {
    private const val CHANNEL_ID = "labprobe_ai_assistant"
    private var nextNotifyId = 4200

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
    fun notifyAssistantMessage(context: Context, title: String, content: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val openApp = PendingIntent.getActivity(
            context,
            4300,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_route", "ai_chat")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_labprobe)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val id = nextNotifyId
        nextNotifyId = if (nextNotifyId >= 2_000_000_000) 4200 else nextNotifyId + 1
        manager.notify(id, notification)
        return true
    }
}
