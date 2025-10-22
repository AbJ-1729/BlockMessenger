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
import java.util.UUID
import kotlin.collections.HashMap

class BleMeshManager(
    ctx: Context,
    private val serviceUuid: UUID,
    private val onMessage: (String) -> Unit
) {
    private val TAG = "BleMeshManager"
    private val appCtx = ctx.applicationContext
    private val adapter: BluetoothAdapter =
        (appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private val advertiser get() = adapter.bluetoothLeAdvertiser

    // BLE limits
    private val maxAdvBytes = 31
    private val headerSize = 6 // 4(id ascii) +1(total) +1(index)

    // Incoming chunk assembly
    private val chunkBuf = HashMap<String, HashMap<Int, ByteArray>>()   // id -> idx->payload
    private val chunkTotals = HashMap<String, Int>()                    // id -> total
    private val chunkTs = HashMap<String, Long>()                       // id -> first ts
    private val chunkTimeoutMs = 10_000L

    // Outgoing rotation
    private data class OutMsg(
        val key: String,
        val chunks: List<ByteArray>,
        var chunkIndex: Int,
        var expiresAt: Long
    )
    private val outMessages = ArrayList<OutMsg>()
    private val rebroadcastTtlMsDefault = 60_000L
    private val maxQueuedMessages = 50
    private var msgRoundRobinIndex = 0
    private val rotateMs = 300L

    private val handler = Handler(Looper.getMainLooper())
    private var loopRunning = false
    private var advCallback: AdvertiseCallback? = null

    private val parcelUuid = ParcelUuid(serviceUuid)
    private val scanFilter = listOf(
        ScanFilter.Builder().setServiceUuid(parcelUuid).build()
    )
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    private val advSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(false)
        .build()

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(appCtx, p) == PackageManager.PERMISSION_GRANTED

    // Periodic cleanup of stale partial messages
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val stale = chunkTs.filterValues { now - it > chunkTimeoutMs }.keys
            if (stale.isNotEmpty()) Log.d(TAG, "Cleanup stale $stale")
            stale.forEach {
                chunkBuf.remove(it)
                chunkTotals.remove(it)
                chunkTs.remove(it)
            }
            handler.postDelayed(this, chunkTimeoutMs)
        }
    }

    // Advertising rotation loop
    private val loopRunnable = object : Runnable {
        override fun run() {
            try {
                tickBroadcast()
            } finally {
                if (loopRunning) handler.postDelayed(this, rotateMs)
            }
        }
    }

    // Scanner callback
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord ?: return
            val data = rec.getServiceData(parcelUuid) ?: return
            if (data.size < headerSize) return

            try {
                val idBytes = data.copyOfRange(0, 4)
                val total = data[4].toInt() and 0xFF
                val index = data[5].toInt() and 0xFF
                if (total <= 0 || index >= total) return
                val id = String(idBytes, Charsets.US_ASCII)
                val chunkPayload = data.copyOfRange(headerSize, data.size)

                val now = System.currentTimeMillis()
                chunkTs.putIfAbsent(id, now)
                val map = chunkBuf.getOrPut(id) { HashMap() }
                if (map.containsKey(index)) return
                map[index] = chunkPayload
                chunkTotals[id] = total

                if (map.size == total) {
                    // Reassemble
                    val ordered = (0 until total).map { idx ->
                        map[idx] ?: return
                    }
                    val fullBytes = ordered.fold(ByteArray(0)) { acc, b -> acc + b }
                    val msg = try {
                        String(fullBytes, Charsets.UTF_8)
                    } catch (e: Exception) {
                        Log.w(TAG, "Decode full msg fail: ${e.message}")
                        null
                    }
                    // Cleanup
                    chunkBuf.remove(id); chunkTotals.remove(id); chunkTs.remove(id)
                    if (msg != null) {
                        Log.i(TAG, "Reassembled len=${msg.length}")
                        onMessage(msg)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "scan parse error: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed $errorCode")
        }
    }

    init {
        handler.postDelayed(cleanupRunnable, chunkTimeoutMs)
    }

    fun startScan() {
        if (!adapter.isEnabled) { Log.w(TAG, "startScan: BT off"); return }
        if (!has(Manifest.permission.BLUETOOTH_SCAN)) { Log.w(TAG, "startScan: no SCAN perm"); return }
        try {
            scanner?.stopScan(scanCb)
        } catch (_: SecurityException) {}
        catch (_: Exception) {}
        try {
            scanner?.startScan(scanFilter, scanSettings, scanCb)
            Log.i(TAG, "Scanning started")
        } catch (se: SecurityException) {
            Log.e(TAG, "startScan security: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "startScan error: ${e.message}")
        }
    }

    fun stopScan() {
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    fun enqueueMessage(serialized: String, ttlMs: Long = rebroadcastTtlMsDefault) {
        if (!adapter.isEnabled) return
        if (!has(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        val chunks = chunk(serialized)
        if (chunks.isEmpty()) return
        synchronized(outMessages) {
            if (outMessages.any { it.key == serialized }) {
                // Refresh TTL
                outMessages.first { it.key == serialized }.expiresAt = System.currentTimeMillis() + ttlMs
                return
            }
            if (outMessages.size >= maxQueuedMessages) {
                outMessages.sortBy { it.expiresAt }
                outMessages.removeFirst()
            }
            outMessages.add(
                OutMsg(
                    key = serialized,
                    chunks = chunks,
                    chunkIndex = 0,
                    expiresAt = System.currentTimeMillis() + ttlMs
                )
            )
            Log.d(TAG, "Enqueued msg chunks=${chunks.size}")
        }
        startLoopIfNeeded()
    }

    fun stopAllBroadcasts() {
        loopRunning = false
        handler.removeCallbacks(loopRunnable)
        stopCurrentAdvert()
        synchronized(outMessages) { outMessages.clear() }
    }

    fun shutdown() {
        stopAllBroadcasts()
        stopScan()
        handler.removeCallbacksAndMessages(null)
        chunkBuf.clear(); chunkTotals.clear(); chunkTs.clear()
    }

    private fun startLoopIfNeeded() {
        if (loopRunning) return
        loopRunning = true
        handler.post(loopRunnable)
    }

    private fun tickBroadcast() {
        synchronized(outMessages) {
            val now = System.currentTimeMillis()
            outMessages.removeAll { it.expiresAt < now }
            if (outMessages.isEmpty()) {
                stopCurrentAdvert()
                loopRunning = false
                return
            }
            if (msgRoundRobinIndex >= outMessages.size) msgRoundRobinIndex = 0
            val msg = outMessages[msgRoundRobinIndex]
            val chunk = msg.chunks[msg.chunkIndex]
            msg.chunkIndex = (msg.chunkIndex + 1) % msg.chunks.size
            if (msg.chunkIndex == 0) {
                msgRoundRobinIndex = (msgRoundRobinIndex + 1) % outMessages.size
            }
            advertiseChunk(chunk)
        }
    }

    private fun advertiseChunk(chunk: ByteArray) {
        try { stopCurrentAdvert() } catch (_: Exception) {}
        advCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "adv fail $errorCode")
            }
        }
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(parcelUuid)
            .addServiceData(parcelUuid, chunk)
            .build()
        try {
            advertiser?.startAdvertising(advSettings, data, advCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "adv security: ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "adv error: ${e.message}")
        }
    }

    private fun stopCurrentAdvert() {
        try { advCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) {}
        advCallback = null
    }

    private fun chunk(msg: String): List<ByteArray> {
        val bytes = msg.toByteArray(Charsets.UTF_8)
        val payloadMax = maxAdvBytes - headerSize
        if (payloadMax <= 0) return emptyList()
        val total = (bytes.size + payloadMax - 1) / payloadMax
        val id = UUID.randomUUID().toString().substring(0, 4) // 4 ascii chars
        val out = ArrayList<ByteArray>(total)
        for (i in 0 until total) {
            val s = i * payloadMax
            val e = minOf(s + payloadMax, bytes.size)
            val part = bytes.copyOfRange(s, e)
            val header = id.toByteArray(Charsets.US_ASCII) // 4 bytes
            val meta = byteArrayOf(total.toByte(), i.toByte())
            val packet = header + meta + part
            if (packet.size <= maxAdvBytes) out.add(packet)
        }
        return out
    }
}