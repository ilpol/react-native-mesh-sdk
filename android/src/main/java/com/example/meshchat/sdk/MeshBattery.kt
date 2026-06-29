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
 * Помощник для исключения приложения из оптимизации батареи (Doze/App Standby).
 *
 * На агрессивных прошивках (Vivo, Xiaomi, Huawei и т.п.) система убивает фоновые
 * процессы — даже с foreground-сервисом — пока приложение не добавлено в «белый
 * список» энергосбережения. Это главный практический способ пережить смахивание
 * приложения и долгий фон.
 */
object MeshBattery {

    private const val TAG = "MeshBattery"

    /** Уже исключено из оптимизации батареи? */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Показать системный диалог «разрешить работу без ограничений батареи».
     * Если он недоступен — открыть общий экран настроек оптимизации батареи.
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
            Log.w(TAG, "Direct request failed (${t.message}); открываю общий экран настроек")
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (t2: Throwable) {
                Log.e(TAG, "Не удалось открыть настройки оптимизации батареи: ${t2.message}")
            }
        }
    }
}
