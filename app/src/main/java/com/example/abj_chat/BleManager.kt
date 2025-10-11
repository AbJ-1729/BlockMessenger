package com.example.abj_chat

import android.Manifest
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID

class BleManager(private val context: Context,private val bluetoothLeAdvertiser: BluetoothLeAdvertiser) {

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertise(serviceUuid: String, deviceName: String, duration: Long) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
            .build()

        bluetoothLeAdvertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i("BLE", "Advertising started successfully with name: $deviceName")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("BLE", "Advertising failed with error code: $errorCode")
            }
        })

        // Stop advertising after the specified duration
        Thread.sleep(duration)
        bluetoothLeAdvertiser.stopAdvertising(object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i("BLE", "Advertising stoped successfully with name: $deviceName")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("BLE", "Advertising stop failed with error code: $errorCode")
            }
        })
    }
}