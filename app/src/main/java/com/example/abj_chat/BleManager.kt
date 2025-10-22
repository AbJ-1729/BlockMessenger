package com.example.abj_chat

import android.Manifest
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID

class BleManager(private val bluetoothLeAdvertiser: BluetoothLeAdvertiser) {

    private val TAG = "BleManager"

    private var currentCallback: AdvertiseCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rotateRunnable: Runnable? = null
    private var rotatePeriodMs: Long = 350L
    private var rotateIndex = 0
    private var rotatingChunks: List<ByteArray> = emptyList()
    private var rotatingServiceUuid: UUID? = null

    private val MIN_SERVICE_DATA_HEADER = 6 // 4(id)+1(total)+1(index)

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertise(
        serviceUuid: UUID,
        serviceData: ByteArray,
        durationMs: Long = 0,
        connectable: Boolean = false
    ) {
        stopAdvertising()
        if (serviceData.size < MIN_SERVICE_DATA_HEADER) {
            Log.w(TAG, "serviceData too short (${serviceData.size})")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(connectable)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), serviceData)
            .build()

        currentCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "Single advertise started")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Single advertise failed: $errorCode")
            }
        }

        try {
            bluetoothLeAdvertiser.startAdvertising(settings, data, currentCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "Security error advertise: ${se.message}")
            currentCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error advertise: ${e.message}")
            currentCallback = null
        }

        if (durationMs > 0) {
            mainHandler.postDelayed({ stopAdvertising() }, durationMs)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertiseChunksLoop(
        serviceUuid: UUID,
        chunks: List<ByteArray>,
        periodMs: Long = 350L,
        durationMs: Long = 0L
    ) {
        stopAdvertising()
        if (chunks.isEmpty()) {
            Log.w(TAG, "No chunks")
            return
        }
        if (chunks.any { it.size < MIN_SERVICE_DATA_HEADER }) {
            Log.w(TAG, "Some chunks shorter than header")
        }

        rotatingChunks = chunks
        rotatingServiceUuid = serviceUuid
        rotatePeriodMs = periodMs
        rotateIndex = 0

        rotateRunnable = object : Runnable {
            override fun run() {
                try {
                    currentCallback?.let {
                        try { bluetoothLeAdvertiser.stopAdvertising(it) } catch (_: Exception) {}
                    }
                    val uuid = rotatingServiceUuid ?: return
                    val chunk = rotatingChunks[rotateIndex]
                    rotateIndex = (rotateIndex + 1) % rotatingChunks.size

                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(false)
                        .build()

                    val data = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addServiceUuid(ParcelUuid(uuid))
                        .addServiceData(ParcelUuid(uuid), chunk)
                        .build()

                    currentCallback = object : AdvertiseCallback() {
                        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                            Log.d(TAG, "Chunk rotate idx=$rotateIndex/${rotatingChunks.size}")
                        }

                        override fun onStartFailure(errorCode: Int) {
                            Log.e(TAG, "Chunk advertise failed: $errorCode")
                        }
                    }
                    bluetoothLeAdvertiser.startAdvertising(settings, data, currentCallback)
                } catch (se: SecurityException) {
                    Log.e(TAG, "Security rotating: ${se.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Rotate error: ${e.message}")
                } finally {
                    mainHandler.postDelayed(this, rotatePeriodMs)
                }
            }
        }

        mainHandler.post(rotateRunnable!!)
        if (durationMs > 0) {
            mainHandler.postDelayed({ stopAdvertising() }, durationMs)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopAdvertising() {
        rotateRunnable?.let {
            mainHandler.removeCallbacks(it)
            rotateRunnable = null
        }
        rotatingChunks = emptyList()
        rotatingServiceUuid = null
        try {
            currentCallback?.let { bluetoothLeAdvertiser.stopAdvertising(it) }
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: ${e.message}")
        } finally {
            currentCallback = null
        }
    }

    fun shutdown() {
        try { stopAdvertising() } catch (_: Exception) {}
        try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        rotateRunnable = null
        rotatingChunks = emptyList()
        rotatingServiceUuid = null
        currentCallback = null
    }
}