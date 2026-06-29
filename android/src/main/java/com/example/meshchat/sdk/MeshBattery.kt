package com.example.meshchat.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper for exempting the app from battery optimization (Doze/App Standby).
 *
 * On aggressive firmware (Vivo, Xiaomi, Huawei, etc.) the system kills background
 * processes — even with a foreground service — until the app is added to the power-saving
 * "whitelist". This is the main practical way to survive the app being swiped away
 * and long background runs.
 */
object MeshBattery {

    private const val TAG = "MeshBattery"

    /** Already exempt from battery optimization? */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Show the system dialog "allow running without battery restrictions".
     * If it's unavailable, open the general battery-optimization settings screen.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations(context)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Direct request failed (${t.message}); opening the general settings screen")
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed to open battery optimization settings: ${t2.message}")
            }
        }
    }
}
