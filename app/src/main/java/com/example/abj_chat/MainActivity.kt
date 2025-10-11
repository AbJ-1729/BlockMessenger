package com.example.abj_chat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashSet

class MainActivity : AppCompatActivity() {

    // BLE and permissions
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private val serviceUuid = UUID.fromString("0000ab11-0000-1000-8000-00805f9b34fb") // Custom UUID for identification
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val requestCodePermissions = 1

    // App logic
    private val maxChunkSize = 20 // BLE manufacturer data limit
    private val localMessages = mutableListOf<Message>()
    private val messageSet = HashSet<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var currentBroadcastMessage: String? = null

    // For chunk reassembly
    private val receivedChunks = mutableMapOf<String, SortedMap<Int, String>>() // msgId -> (chunkIdx -> data)
    private val receivedChunkCounts = mutableMapOf<String, Int>() // msgId -> totalChunks

    // Identification
    private val ADVERTISE_IDENTIFIER = "ABJCHAT" // 7 bytes, unique for this app

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, permissions, requestCodePermissions)
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        // Set device name for user-friendliness (not for filtering)
        bluetoothAdapter.name = "ABJ_Chat"

        val sendEditText = findViewById<EditText>(R.id.sendEditText)
        val sendButton = findViewById<Button>(R.id.sendButton)
        val receiveEditText = findViewById<EditText>(R.id.receiveEditText)

        sendButton.setOnClickListener {
            val message = sendEditText.text.toString()
            if (message.isNotBlank()) {
                val timestamp = System.currentTimeMillis()
                val newMessage = Message(message, timestamp)
                addMessage(newMessage)
                updateBroadcastMessage()
                Toast.makeText(this, "Broadcasting updated messages", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enter a message to send", Toast.LENGTH_SHORT).show()
            }
        }

        startScanning(receiveEditText)
        updateBroadcastMessage()
    }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- BLE Advertising ---

    private fun updateBroadcastMessage() {
        val broadcastMessage = buildBroadcastMessage()
        if (broadcastMessage != currentBroadcastMessage) {
            currentBroadcastMessage = broadcastMessage
            startAdvertising(broadcastMessage)
        }
    }

    private fun buildBroadcastMessage(): String {
        // Format: ABJCHAT|msg1_ts1,msg2_ts2,...
        val joined = localMessages.joinToString(",") { "${it.message}_${it.timestamp}" }
        return "$ADVERTISE_IDENTIFIER|$joined"
    }

    private fun startAdvertising(broadcastMessage: String) {
        stopAdvertising()

        val chunks = splitMessageIntoChunks(broadcastMessage)
        advertiseChunksLoop(chunks)
    }

    private fun stopAdvertising() {
        if (::bluetoothLeAdvertiser.isInitialized) {
            try {
                bluetoothLeAdvertiser.stopAdvertising(object : AdvertiseCallback() {})
            } catch (_: Exception) {}
        }
    }

    private fun splitMessageIntoChunks(message: String): List<ByteArray> {
        // Each chunk: [IDENTIFIER][MSGID][IDX][TOTAL][DATA]
        // IDENTIFIER: 7 bytes, MSGID: 4 bytes, IDX: 1 byte, TOTAL: 1 byte, DATA: up to 7 bytes
        // Total: 20 bytes
        val msgId = (message.hashCode() and 0xFFFFFFFF.toInt()).toString(16).padStart(4, '0').take(4)
        val dataBytes = message.toByteArray(Charsets.UTF_8)
        val maxDataPerChunk = 7
        val totalChunks = (dataBytes.size + maxDataPerChunk - 1) / maxDataPerChunk
        val chunks = mutableListOf<ByteArray>()
        for (i in 0 until totalChunks) {
            val start = i * maxDataPerChunk
            val end = minOf(start + maxDataPerChunk, dataBytes.size)
            val chunkData = dataBytes.sliceArray(start until end)
            val chunk = ByteArray(7 + 4 + 1 + 1 + chunkData.size)
            // IDENTIFIER
            System.arraycopy(ADVERTISE_IDENTIFIER.toByteArray(Charsets.UTF_8), 0, chunk, 0, 7)
            // MSGID
            System.arraycopy(msgId.toByteArray(Charsets.UTF_8), 0, chunk, 7, 4)
            // IDX
            chunk[11] = i.toByte()
            // TOTAL
            chunk[12] = totalChunks.toByte()
            // DATA
            System.arraycopy(chunkData, 0, chunk, 13, chunkData.size)
            chunks.add(chunk)
        }
        return chunks
    }

    private fun advertiseChunksLoop(chunks: List<ByteArray>) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        var chunkIndex = 0

        fun advertiseNext() {
            if (chunks.isEmpty()) return
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(serviceUuid))
                .addManufacturerData(0, chunks[chunkIndex])
                .build()

            bluetoothLeAdvertiser.stopAdvertising(object : AdvertiseCallback() {})
            bluetoothLeAdvertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.i("BLE", "Advertising chunk ${chunkIndex + 1}/${chunks.size}")
                    window.decorView.postDelayed({
                        bluetoothLeAdvertiser.stopAdvertising(this)
                        chunkIndex = (chunkIndex + 1) % chunks.size
                        advertiseNext()
                    }, 250L)
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e("BLE", "Advertising failed: $errorCode")
                }
            })
        }
        advertiseNext()
    }

    // --- BLE Scanning ---

    private fun startScanning(receiveEditText: EditText) {
        if (!bluetoothAdapter.isEnabled || !hasPermissions() || !::bluetoothLeScanner.isInitialized) {
            Log.e("BLE", "Cannot start scanning: Bluetooth not enabled or permissions missing.")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()

        try {
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val scanRecord = result.scanRecord ?: return
                    val data = scanRecord.getManufacturerSpecificData(0) ?: return
                    if (data.size < 13) return // Not a valid chunk
                    val identifier = data.copyOfRange(0, 7).toString(Charsets.UTF_8)
                    if (identifier != ADVERTISE_IDENTIFIER) return // Not our app's packet

                    val msgId = data.copyOfRange(7, 11).toString(Charsets.UTF_8)
                    val idx = data[11].toInt() and 0xFF
                    val total = data[12].toInt() and 0xFF
                    val chunkData = data.copyOfRange(13, data.size)

                    val chunkMap = receivedChunks.getOrPut(msgId) { sortedMapOf() }
                    chunkMap[idx] = chunkData.toString(Charsets.UTF_8)
                    receivedChunkCounts[msgId] = total

                    // If all chunks received, reassemble
                    if (chunkMap.size == total) {
                        val fullMsg = (0 until total).joinToString("") { chunkMap[it] ?: "" }
                        processReceivedMessage(fullMsg, receiveEditText)
                        receivedChunks.remove(msgId)
                        receivedChunkCounts.remove(msgId)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.e("BLE", "Scan failed: $errorCode")
                }
            })
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException: ${e.message}")
        }
    }

    private fun processReceivedMessage(fullMsg: String, receiveEditText: EditText) {
        if (!fullMsg.startsWith("$ADVERTISE_IDENTIFIER|")) return
        val payload = fullMsg.removePrefix("$ADVERTISE_IDENTIFIER|")
        val receivedMessages = if (payload.isNotBlank()) payload.split(",") else emptyList()
        var isUpdated = false
        for (id in receivedMessages) {
            val parts = id.split("_")
            if (parts.size == 2) {
                val message = parts[0]
                val timestamp = parts[1].toLongOrNull()
                if (timestamp != null) {
                    val newMessage = Message(message, timestamp)
                    if (!messageSet.contains(newMessage.id)) {
                        addMessage(newMessage)
                        isUpdated = true
                    }
                }
            }
        }
        if (isUpdated) {
            runOnUiThread {
                receiveEditText.setText(localMessages.joinToString("\n") {
                    "${it.message} (${dateFormat.format(Date(it.timestamp))})"
                })
            }
            updateBroadcastMessage()
        }
    }

    private fun addMessage(message: Message) {
        if (!messageSet.contains(message.id)) {
            localMessages.add(message)
            messageSet.add(message.id)
            localMessages.sortBy { it.timestamp }
        }
    }

    data class Message(val message: String, val timestamp: Long) {
        val id: String
            get() = "${message}_$timestamp"
    }
}
