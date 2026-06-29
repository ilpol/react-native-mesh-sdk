package com.example.meshchat.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground-сервис, удерживающий процесс приложения живым, пока активен mesh.
 *
 * Сам BLE-mesh (BluetoothMeshService) работает в процессе приложения и
 * управляется из [MeshChatSdk]; задача сервиса — не дать Android усыпить/убить
 * процесс при свёрнутом приложении или погашенном экране, чтобы транспорт
 * продолжал сканировать, держать GATT-соединения и ретранслировать пакеты.
 *
 * Тип сервиса — connectedDevice (работа с BLE-устройствами поблизости).
 */
class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val CHANNEL_ID = "meshchat_foreground"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_START = "com.example.meshchat.sdk.START"
        const val ACTION_STOP = "com.example.meshchat.sdk.STOP"

        /** Заголовок/текст уведомления — можно переопределить до запуска. */
        @Volatile var notificationTitle: String = "MeshChat активен"
        @Volatile var notificationText: String = "Обмен сообщениями по Bluetooth-сети"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ВАЖНО: если startForeground упадёт (особенности OEM/версии Android,
                // не выданное уведомление, ограничения FGS), нельзя ронять процесс —
                // иначе вместе с ним умрёт mesh и сообщения перестанут приходить.
                try {
                    startForegroundCompat(buildNotification())
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground failed; продолжаем без foreground: ${t.message}", t)
                    // Снимаем «обещание» промоушена, чтобы не получить ANR/таймаут-краш.
                    try { stopForegroundCompat() } catch (_: Throwable) {}
                    try { stopSelf() } catch (_: Throwable) {}
                    return START_NOT_STICKY
                }
            }
        }
        // START_STICKY: если систему всё же убьёт процесс, она попробует пересоздать сервис.
        return START_STICKY
    }

    /**
     * Приложение смахнули из «недавних». Foreground-сервис по умолчанию НЕ
     * привязан к задаче, поэтому процесс (и mesh в нём) продолжает жить — мы
     * лишь переутверждаем уведомление и НЕ останавливаемся. На агрессивных
     * прошивках ОС всё равно может убить процесс; против этого помогает
     * исключение приложения из оптимизации батареи (см. SDK API).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: задачу смахнули, держим сервис и mesh живыми")
        try {
            startForegroundCompat(buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "re-assert foreground after task removed failed: ${t.message}")
        }
        // НЕ вызываем super-поведение, которое могло бы остановить сервис.
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPI = launch?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .apply { contentPI?.let { setContentIntent(it) } }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "MeshChat",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Удерживает mesh-сеть активной в фоне"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
