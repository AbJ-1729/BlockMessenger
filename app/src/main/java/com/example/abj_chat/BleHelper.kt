// kotlin
    // File: `app/src/main/java/com/example/abj_chat/BleHelper.kt`
    package com.example.abj_chat

    import android.Manifest
    import android.bluetooth.BluetoothAdapter
    import android.bluetooth.BluetoothManager
    import android.bluetooth.le.*
    import android.content.Context
    import android.content.pm.PackageManager
    import android.os.Handler
    import android.os.Looper
    import android.os.ParcelUuid
    import android.util.Log
    import androidx.core.content.ContextCompat
    import java.util.*
    import kotlin.collections.HashMap
    import kotlin.concurrent.timerTask

    class BleHelper(
        private val context: Context,
        private val serviceUuid: UUID,
        private val onMessage: (String) -> Unit
    ) {
        private val TAG = "BleHelper"
        private val maxAdvertiseBytes = 31 // total AD length; we keep conservative chunk size
        private val maxChunkSize = 20 // payload per chunk (fits common constraints)
        private val headerSize = 4 + 1 + 1 // id(4) + total(1) + idx(1)

        private val bluetoothAdapter: BluetoothAdapter?
            get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        private val bluetoothLeScanner: BluetoothLeScanner?
            get() = bluetoothAdapter?.bluetoothLeScanner

        private val bluetoothLeAdvertiser: BluetoothLeAdvertiser?
            get() = bluetoothAdapter?.bluetoothLeAdvertiser

        // Scan chunk reassembly structures
        private val chunkBuffer = HashMap<String, MutableMap<Int, ByteArray>>()
        private val chunkTotal = HashMap<String, Int>()
        private val chunkTimestamps = HashMap<String, Long>()
        private val chunkTimeoutMs = 30_000L
        private val cleanupHandler = Handler(Looper.getMainLooper())
        private val cleanupRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val expired = chunkTimestamps.filterValues { now - it > chunkTimeoutMs }.keys.toList()
                for (id in expired) {
                    chunkBuffer.remove(id)
                    chunkTotal.remove(id)
                    chunkTimestamps.remove(id)
                }
                cleanupHandler.postDelayed(this, chunkTimeoutMs)
            }
        }

        // Advertising loop
        private var advertiseTimer: Timer? = null
        private var advertiseCallback: AdvertiseCallback? = null

        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord ?: return

                // Prefer service data (advertiser uses addServiceData)
                val sd = rec.getServiceData(ParcelUuid(serviceUuid))
                if (sd == null || sd.size < headerSize) return

                try {
                    val id = String(sd.sliceArray(0 until 4), Charsets.UTF_8)
                    val total = sd[4].toInt() and 0xFF
                    val idx = sd[5].toInt() and 0xFF
                    val data = sd.sliceArray(6 until sd.size)

                    val buf = chunkBuffer.getOrPut(id) { HashMap() }
                    buf[idx] = data
                    chunkTotal[id] = total
                    chunkTimestamps[id] = System.currentTimeMillis()

                    if (buf.size == total) {
                        val assembled = (0 until total).flatMap { i ->
                            buf[i]?.toList() ?: emptyList()
                        }.toByteArray()
                        val message = String(assembled, Charsets.UTF_8)
                        Log.i(TAG, "Reassembled message: $message")
                        // cleanup
                        chunkBuffer.remove(id)
                        chunkTotal.remove(id)
                        chunkTimestamps.remove(id)
                        // deliver
                        onMessage(message)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing serviceData chunk: ${e.message}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
            }
        }

        init {
            cleanupHandler.postDelayed(cleanupRunnable, chunkTimeoutMs)
        }

        fun hasAdvertisePermission(): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        }

        fun hasScanPermission(): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        }

        fun startScanning() {
            val adapter = bluetoothAdapter ?: run {
                Log.e(TAG, "No Bluetooth adapter")
                return
            }
            if (!adapter.isEnabled) {
                Log.w(TAG, "Bluetooth disabled")
                return
            }
            if (!hasScanPermission()) {
                Log.w(TAG, "Missing scan permission")
                return
            }
            val scanner = bluetoothLeScanner ?: run {
                Log.e(TAG, "No BLE scanner")
                return
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Try service UUID filter first (less noise). If no results seen, caller can change to full scan.
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()

            try {
                try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
                scanner.startScan(listOf(filter), settings, scanCallback)
                Log.i(TAG, "Started scanning with service UUID filter")
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException starting scan: ${se.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start scan: ${e.message}")
            }
        }

        fun stopScanning() {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan error: ${e.message}")
            }
        }

        fun advertiseMessageLoop(broadcast: String) {
            advertiseTimer?.cancel()
            advertiseTimer = null

            val chunks = splitIntoChunks(broadcast)
            if (chunks.isEmpty()) return
            if (!hasAdvertisePermission()) {
                Log.w(TAG, "Missing advertise permission")
                return
            }
            val advertiser = bluetoothLeAdvertiser ?: run {
                Log.e(TAG, "No advertiser available")
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    // no-op
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "Advertise startFailed: $errorCode")
                }
            }

            val handler = Handler(Looper.getMainLooper())
            var index = 0
            advertiseTimer = Timer()
            advertiseTimer?.scheduleAtFixedRate(timerTask {
                val chunk = chunks[index]
                index = (index + 1) % chunks.size

                handler.post {
                    try {
                        try { advertiser.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
                        val data = AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .setIncludeTxPowerLevel(false)
                            .addServiceUuid(ParcelUuid(serviceUuid))  // include UUID so scans with filter see it
                            .addServiceData(ParcelUuid(serviceUuid), chunk)
                            .build()
                        advertiser.startAdvertising(settings, data, advertiseCallback)
                    } catch (se: SecurityException) {
                        Log.e(TAG, "Advertise SecurityException: ${se.message}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Advertise error: ${e.message}")
                    }
                }
            }, 0, 350)
        }

        fun stopAdvertising() {
            try {
                advertiseTimer?.cancel()
                advertiseTimer = null
                try { bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
                advertiseCallback = null
            } catch (e: Exception) {
                Log.w(TAG, "stopAdvertising error: ${e.message}")
            }
        }

        private fun splitIntoChunks(message: String): List<ByteArray> {
            val data = message.toByteArray(Charsets.UTF_8)
            val maxPayload = maxChunkSize - headerSize
            if (maxPayload <= 0) return emptyList()
            val total = (data.size + maxPayload - 1) / maxPayload
            val id = UUID.randomUUID().toString().substring(0, 4)
            val out = ArrayList<ByteArray>()
            for (i in 0 until total) {
                val start = i * maxPayload
                val end = minOf(start + maxPayload, data.size)
                val body = data.sliceArray(start until end)
                val header = id.toByteArray(Charsets.UTF_8)
                val meta = byteArrayOf(total.toByte(), i.toByte())
                val chunk = header + meta + body
                if (chunk.size > maxChunkSize) {
                    Log.w(TAG, "chunk too big; skipping")
                    continue
                }
                out.add(chunk)
            }
            return out
        }

        fun shutdown() {
            stopAdvertising()
            stopScanning()
            cleanupHandler.removeCallbacks(cleanupRunnable)
        }
    }