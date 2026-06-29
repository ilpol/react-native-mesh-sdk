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
 * Foreground service that keeps the app process alive while mesh is active.
 *
 * The BLE mesh itself (BluetoothMeshService) runs in the app process and is
 * managed from [MeshChatSdk]; the service's job is to stop Android from sleeping/killing
 * the process when the app is minimized or the screen is off, so the transport
 * keeps scanning, holding GATT connections, and relaying packets.
 *
 * The service type is connectedDevice (working with nearby BLE devices).
 */
class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val CHANNEL_ID = "meshchat_foreground"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_START = "com.example.meshchat.sdk.START"
        const val ACTION_STOP = "com.example.meshchat.sdk.STOP"

        /** Notification title/text — can be overridden before start. */
        @Volatile var notificationTitle: String = "MeshChat active"
        @Volatile var notificationText: String = "Messaging over the Bluetooth network"

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
                // IMPORTANT: if startForeground fails (OEM/Android-version quirks,
                // an ungranted notification, FGS restrictions), we must not crash the process —
                // otherwise mesh dies with it and messages stop arriving.
                try {
                    startForegroundCompat(buildNotification())
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground failed; continuing without foreground: ${t.message}", t)
                    // Drop the promotion "promise" to avoid an ANR/timeout crash.
                    try { stopForegroundCompat() } catch (_: Throwable) {}
                    try { stopSelf() } catch (_: Throwable) {}
                    return START_NOT_STICKY
                }
            }
        }
        // START_STICKY: if the system kills the process anyway, it will try to recreate the service.
        return START_STICKY
    }

    /**
     * The app was swiped away from "recents". A foreground service is NOT bound
     * to the task by default, so the process (and mesh in it) keeps living — we
     * just re-assert the notification and do NOT stop. On aggressive firmware the
     * OS may still kill the process; exempting the app from battery optimization
     * helps against that (see the SDK API).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: task swiped away, keeping the service and mesh alive")
        try {
            startForegroundCompat(buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "re-assert foreground after task removed failed: ${t.message}")
        }
        // Do NOT call the super behavior, which could stop the service.
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
                    description = "Keeps the mesh network active in the background"
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
