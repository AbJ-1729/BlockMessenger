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
    private val messageSet = HashSet<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var currentBroadcastMessage: String? = null
    private val deviceNamePrefix = "ab"

    private lateinit var usernameEditText: EditText
    private lateinit var sendEditText: EditText
    private lateinit var receiveEditText: EditText

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
        if (::bluetoothLeAdvertiser.isInitialized) {
            try {
                bluetoothLeAdvertiser.stopAdvertising(object : AdvertiseCallback() {})
            } catch (_: Exception) {}
        }
    }

    private fun splitMessageIntoChunks(message: String): List<ByteArray> {
        val dataBytes = message.toByteArray(Charsets.UTF_8)
        val totalChunks = (dataBytes.size + maxChunkSize - 1) / maxChunkSize
        val chunks = mutableListOf<ByteArray>()
        for (i in 0 until totalChunks) {
            val start = i * maxChunkSize
            val end = minOf(start + maxChunkSize, dataBytes.size)
            chunks.add(dataBytes.sliceArray(start until end))
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
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val scanRecord = result.scanRecord ?: return
                    val data = scanRecord.getManufacturerSpecificData(0)
                    if (data == null) {
                        Log.d("BLE", "No manufacturer data found in scan result")
                        return
                    }
                    val message = String(data, Charsets.UTF_8)
                    Log.i("BLE", "Received manufacturer data: $message")
                    processReceivedMessage(message)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e("BLE", "Scan failed: $errorCode")
                }
            })
        } catch (e: SecurityException) {
            Log.e("BLE", "SecurityException: ${e.message}")
        }
    }

    private fun processReceivedMessage(message: String) {
        val receivedMessages = message.split(",")
        val minValidTimestamp = 1577836800000L // 2020-01-01 00:00:00 UTC in millis
        var isUpdated = false
        for (entry in receivedMessages) {
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
                        if (!messageSet.contains(newMessage.id)) {
                            addMessage(newMessage)
                            isUpdated = true
                        }
                    }
                }
            }
        }
        runOnUiThread {
            receiveEditText.setText(localMessages.joinToString("\n") {
                "${it.username}: ${it.message} (${dateFormat.format(Date(it.timestamp))})"
            })
        }
    }

    private fun addMessage(message: Message) {
        if (!messageSet.contains(message.id)) {
            localMessages.add(message)
            messageSet.add(message.id)
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
