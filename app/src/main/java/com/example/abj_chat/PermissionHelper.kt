package com.example.abj_chat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {
    const val REQUEST_CODE_PERMISSIONS = 1001

    private fun needRuntimeBlePerms(): Boolean = Build.VERSION.SDK_INT >= 31

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(a: Activity): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(a, it) == PackageManager.PERMISSION_GRANTED
        }

    fun getMissingPermissions(a: Activity): Array<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(a, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun requestPermissions(a: Activity) {
        val miss = getMissingPermissions(a)
        if (miss.isNotEmpty()) {
            ActivityCompat.requestPermissions(a, miss, REQUEST_CODE_PERMISSIONS)
        }
    }
}