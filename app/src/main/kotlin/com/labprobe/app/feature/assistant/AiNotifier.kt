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

/** Foreground flag: while the app is visible, chat bubbles are enough. */
object AiForeground {
    @Volatile
    var visible: Boolean = true
}

/** System notifications for assistant messages that arrive while backgrounded. */
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

    fun notifyAssistantMessage(context: Context, title: String, content: String) {
        if (AiForeground.visible) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openApp = PendingIntent.getActivity(
            context,
            4300,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
    }
}
