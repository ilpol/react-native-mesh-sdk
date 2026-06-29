package com.example.meshchat.sdk

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
import androidx.core.content.ContextCompat

/**
 * Локальные уведомления о входящих сообщениях, когда приложение в фоне.
 * Работает нативно (JS в фоне спит), поэтому пользователь видит сообщение,
 * не открывая приложение.
 */
object MeshNotifier {

    private const val CHANNEL_ID = "meshchat_messages"
    private const val GROUP_KEY = "meshchat_messages_group"

    fun notifyMessage(context: Context, sender: String, content: String, threadKey: String) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPI = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(sender.ifBlank { "Новое сообщение" })
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .apply { contentPI?.let { setContentIntent(it) } }
            .build()

        // Одно уведомление на отправителя (новые перетирают старое от того же узла).
        val id = threadKey.hashCode()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS не выдан — молча игнорируем.
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Сообщения",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Входящие сообщения mesh-чата" }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
