// kotlin
// File: `app/src/main/java/com/example/abj_chat/BleManager.kt`
package com.example.abj_chat

import android.Manifest
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID

class BleManager(private val context: Context, private val bluetoothLeAdvertiser: BluetoothLeAdvertiser) {

    private val TAG = "BleManager"
    private var currentCallback: AdvertiseCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Advertise service-data bytes under the given serviceUuid for 'durationMs' milliseconds.
     * 'serviceData' should be the full service-data payload (header + body) expected by receivers.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertise(serviceUuid: String, serviceData: ByteArray, durationMs: Long, connectable: Boolean = false) {
        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(connectable)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
            .addServiceData(ParcelUuid(UUID.fromString(serviceUuid)), serviceData)
            .build()

        currentCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "Advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }

        try {
            bluetoothLeAdvertiser.startAdvertising(settings, data, currentCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "Start advertising security error: ${se.message}")
            currentCallback = null
            return
        } catch (e: Exception) {
            Log.e(TAG, "Start advertising error: ${e.message}")
            currentCallback = null
            return
        }

        if (durationMs > 0) {
            mainHandler.postDelayed({ stopAdvertising() }, durationMs)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopAdvertising() {
        try {
            currentCallback?.let {
                bluetoothLeAdvertiser.stopAdvertising(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising error: ${e.message}")
        } finally {
            currentCallback = null
        }
    }
}