package com.example.abj_chat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.text.Html
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

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private val serviceUuid = UUID.fromString("0000ab11-0000-1000-8000-00805f9b34fb")
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val requestCodePermissions = 1

    private val maxChunkSize = 20
    private val localMessages = mutableListOf<Message>()
    private val receivedMessages = mutableListOf<Message>()
    private val messageSet = HashSet<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var currentBroadcastMessage: String? = null
    private val deviceNamePrefix = "ab"
    private val maxHistory = 20

    private lateinit var usernameEditText: EditText
    private lateinit var sendEditText: EditText
    private lateinit var receiveEditText: EditText

    // For chunk reassembly
    private val chunkBuffer = mutableMapOf<String, MutableMap<Int, ByteArray>>() // messageId -> (chunkIndex -> data)
    private val chunkMeta = mutableMapOf<String, Int>() // messageId -> totalChunks
    private val chunkTimestamps = mutableMapOf<String, Long>() // messageId -> last received time
    private val chunkTimeoutMs = 30_000L // 30 seconds

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val expired = chunkTimestamps.filterValues { now - it > chunkTimeoutMs }.keys
            for (id in expired) {
                chunkBuffer.remove(id)
                chunkMeta.remove(id)
                chunkTimestamps.remove(id)
            }
            cleanupHandler.postDelayed(this, chunkTimeoutMs)
        }
    }

    // Persistent callbacks
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val data = scanRecord.getManufacturerSpecificData(0)
            if (data == null || data.size < 10) { // 8 for id, 2 for meta
                Log.d("BLE", "No or invalid manufacturer data found in scan result")
                return
            }
            // Parse header
            val messageId = String(data.sliceArray(0..7), Charsets.UTF_8)
            val totalChunks = data[8].toInt() and 0xFF
            val chunkIndex = data[9].toInt() and 0xFF
            val chunkData = data.sliceArray(10 until data.size)

            // Buffer chunk
            val buffer = chunkBuffer.getOrPut(messageId) { mutableMapOf() }
            buffer[chunkIndex] = chunkData
            chunkMeta[messageId] = totalChunks
            chunkTimestamps[messageId] = System.currentTimeMillis()

            // Check if all chunks received
            if (buffer.size == totalChunks) {
                // Reassemble
                val fullData = (0 until totalChunks).flatMap { idx ->
                    buffer[idx]?.toList() ?: emptyList()
                }.toByteArray()
                val message = String(fullData, Charsets.UTF_8)
                Log.i("BLE", "Reassembled message: $message")
                processReceivedMessage(message)
                // Clean up
                chunkBuffer.remove(messageId)
                chunkMeta.remove(messageId)
                chunkTimestamps.remove(messageId)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed: $errorCode")
        }
    }

    private var advertiseCallback: AdvertiseCallback? = null

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

        usernameEditText = findViewById(R.id.usernameEditText)
        sendEditText = findViewById(R.id.sendEditText)
        receiveEditText = findViewById(R.id.receiveEditText)
        val sendButton = findViewById<Button>(R.id.sendButton)

        usernameEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateDeviceName()
        }
        usernameEditText.setOnEditorActionListener { _, _, _ ->
            updateDeviceName()
            false
        }

        sendButton.setOnClickListener {
            val username = usernameEditText.text.toString().trim()
            if (username.isBlank()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            updateDeviceName()
            val message = sendEditText.text.toString().trim()
            if (message.isNotBlank()) {
                // Always use current device time for timestamp
                val timestamp = System.currentTimeMillis()
                val newMessage = Message(username, message, timestamp)
                addMessage(newMessage)
                updateBroadcastMessage()
                Toast.makeText(this, "Broadcasting updated messages", Toast.LENGTH_SHORT).show()
                sendEditText.setText("")
            } else {
                Toast.makeText(this, "Enter a message to send", Toast.LENGTH_SHORT).show()
            }
        }

        cleanupHandler.postDelayed(cleanupRunnable, chunkTimeoutMs)
        startScanning()
        updateBroadcastMessage()
    }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateBroadcastMessage() {
        val broadcastMessage = buildBroadcastMessage()
        if (broadcastMessage != currentBroadcastMessage) {
            currentBroadcastMessage = broadcastMessage
            startAdvertising(broadcastMessage)
        }
    }

    private fun buildBroadcastMessage(): String {
        return localMessages.joinToString(",") { "${it.username}:${it.message}_${it.timestamp}" }
    }

    private fun startAdvertising(broadcastMessage: String) {
        stopAdvertising()

        val chunks = splitMessageIntoChunks(broadcastMessage)
        advertiseChunksLoop(chunks)
    }

    private fun stopAdvertising() {
        if (::bluetoothLeAdvertiser.isInitialized && advertiseCallback != null) {
            try {
                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {}
        }
    }

    private fun splitMessageIntoChunks(message: String): List<ByteArray> {
        // Each chunk: [messageId(8)][totalChunks(1)][chunkIndex(1)][data...]
        // Manufacturer data must be <= 20 bytes (for compatibility)
        val headerSize = 8 + 1 + 1 // 10 bytes
        val maxPayload = maxChunkSize - headerSize // 10 bytes
        val dataBytes = message.toByteArray(Charsets.UTF_8)
        val totalChunks = (dataBytes.size + maxPayload - 1) / maxPayload
        val messageId = UUID.randomUUID().toString().substring(0, 8) // short unique id

        val chunks = mutableListOf<ByteArray>()
        for (i in 0 until totalChunks) {
            val start = i * maxPayload
            val end = minOf(start + maxPayload, dataBytes.size)
            val chunkData = dataBytes.sliceArray(start until end)
            val header = messageId.toByteArray(Charsets.UTF_8)
            val meta = byteArrayOf(totalChunks.toByte(), i.toByte())
            val chunk = header + meta + chunkData
            if (chunk.size > maxChunkSize) {
                Log.e("BLE", "Chunk size ${chunk.size} exceeds max $maxChunkSize bytes, dropping chunk!")
                continue
            }
            chunks.add(chunk)
        }
        return chunks
    }

    private var advertiseChunksTimer: Timer? = null
    private var lastChunks: List<ByteArray>? = null

    private fun advertiseChunksLoop(chunks: List<ByteArray>) {
        advertiseChunksTimer?.cancel()
        lastChunks = chunks
        if (chunks.isEmpty()) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        var chunkIndex = 0
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i("BLE", "Advertising chunk ${chunkIndex + 1}/${chunks.size}")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("BLE", "Advertising failed: $errorCode")
            }
        }

        advertiseChunksTimer = Timer()
        advertiseChunksTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
                } catch (_: Exception) {}
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(serviceUuid))
                    .addManufacturerData(0, chunks[chunkIndex])
                    .build()
                bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
                chunkIndex = (chunkIndex + 1) % chunks.size
            }
        }, 0, 350) // 350ms per chunk
    }

    override fun onDestroy() {
        super.onDestroy()
        advertiseChunksTimer?.cancel()
        stopAdvertising()
        cleanupHandler.removeCallbacks(cleanupRunnable)
    }

    private fun startScanning() {
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
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException: ${e.message}")
        }
    }

    private fun processReceivedMessage(message: String) {
        val receivedMessagesRaw = message.split(",")
        val minValidTimestamp = 1577836800000L // 2020-01-01 00:00:00 UTC in millis
        var isUpdated = false
        for (entry in receivedMessagesRaw) {
            val parts = entry.split(":")
            if (parts.size == 2) {
                val username = parts[0]
                val messageParts = parts[1].split("_")
                if (messageParts.size == 2) {
                    val msg = messageParts[0]
                    val timestamp = messageParts[1].toLongOrNull()
                    // Only accept timestamps after 2020-01-01
                    if (timestamp != null && timestamp > minValidTimestamp) {
                        val newMessage = Message(username, msg, timestamp)
                        // Only add if not sent by this user and not already present
                        if (!messageSet.contains(newMessage.id) && username != usernameEditText.text.toString().trim()) {
                            receivedMessages.add(newMessage)
                            messageSet.add(newMessage.id)
                            if (receivedMessages.size > maxHistory) receivedMessages.removeAt(0)
                            isUpdated = true
                        }
                    }
                }
            }
        }
        runOnUiThread {
            // Show both sent and received messages, sorted by timestamp
            val allMessages = (localMessages + receivedMessages).sortedBy { it.timestamp }
            val formatted = allMessages.joinToString("<br>") {
                "<b>${it.username}:</b> ${it.message} <span style='float:right; font-size:smaller; color:#888;'>${dateFormat.format(Date(it.timestamp))}</span>"
            }
            receiveEditText.setText(Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY))
        }
    }

    private fun addMessage(message: Message) {
        if (!messageSet.contains(message.id)) {
            localMessages.add(message)
            messageSet.add(message.id)
            if (localMessages.size > maxHistory) localMessages.removeAt(0)
            localMessages.sortBy { it.timestamp }
        }
    }

    private fun updateDeviceName() {
        val username = usernameEditText.text.toString().trim()
        if (username.isNotBlank()) {
            bluetoothAdapter.name = "$deviceNamePrefix$username"
        }
    }

    data class Message(
        val username: String,
        val message: String,
        val timestamp: Long
    ) {
        val id: String
            get() = "$username:$message:$timestamp"
    }
}
