package com.bitchat.android.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

/**
 * Handles all Bluetooth permission checking logic
 */
class BluetoothPermissionManager(private val context: Context) {
    
    /**
     * Check if all required Bluetooth permissions are granted
     */
    fun hasBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // On Android 12+ BLUETOOTH_SCAN is declared with neverForLocation, so
            // mesh chat doesn't need location — only the BT permissions.
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            // Before Android 12 BLE scanning is impossible without location.
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }

        return permissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
