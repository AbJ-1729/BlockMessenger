// kotlin
// File: `app/src/main/java/com/example/abj_chat/MainActivity.kt`
package com.example.abj_chat

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Html
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashSet

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val REQUEST_PERMS = 1001
    private val requiredPerms = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )

    private lateinit var usernameEditText: EditText
    private lateinit var recipientEditText: EditText
    private lateinit var sendEditText: EditText
    private lateinit var receiveEditText: EditText
    private lateinit var sendButton: Button

    private lateinit var dbHelper: MessageDbHelper
    private lateinit var db: SQLiteDatabase
    private val messageSet = HashSet<String>()
    private val maxHistory = 200
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val b64Flags = Base64.URL_SAFE or Base64.NO_WRAP

    private var localPublicKey: String? = null
    private var localPrivateKey: String? = null

    private lateinit var bleHelper: BleHelper
    private val serviceUuid = UUID.fromString("0000ab11-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usernameEditText = findViewById(R.id.usernameEditText)
        recipientEditText = findViewById(R.id.recipientEditText)
        sendEditText = findViewById(R.id.sendEditText)
        receiveEditText = findViewById(R.id.receiveEditText)
        sendButton = findViewById(R.id.sendButton)

        dbHelper = MessageDbHelper(this)
        db = dbHelper.writableDatabase

        loadLocalKeys()

        bleHelper = BleHelper(this, serviceUuid) { msg ->
            runOnUiThread {
                try {
                    processReceivedMessage(msg)
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing incoming: ${e.message}")
                }
            }
        }

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPerms, REQUEST_PERMS)
        } else {
            startBleIfReady()
        }

        loadMessagesFromDb()

        sendButton.setOnClickListener {
            val username = usernameEditText.text.toString().trim()
            if (username.isBlank()) {
                Toast.makeText(this, "Enter a username", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val messageText = sendEditText.text.toString().trim()
            if (messageText.isBlank()) { Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            val recipient = recipientEditText.text.toString().trim().ifBlank { "ALL" }
            val timestamp = System.currentTimeMillis()
            val senderId = localPublicKey ?: username

            val payload = if (recipient == "ALL") {
                Base64.encodeToString(messageText.toByteArray(Charsets.UTF_8), b64Flags)
            } else {
                try {
                    val localPriv = localPrivateKey ?: throw IllegalStateException("Local private key missing")
                    EncryptionUtil.encryptFor(recipient, localPriv, messageText)
                } catch (e: Exception) {
                    Toast.makeText(this, "Encryption failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val m = MessageV2(senderId, recipient, payload, timestamp)
            addMessageToDb(m)
            loadMessagesFromDb()
            updateBroadcastMessage()
            sendEditText.setText("")
            Toast.makeText(this, "Queued for broadcast", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPerms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBleIfReady()
            } else {
                Toast.makeText(this, "BLE permissions required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadLocalKeys()
        startBleIfReady()
    }

    override fun onPause() {
        super.onPause()
        bleHelper.stopScanning()
        bleHelper.stopAdvertising()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleHelper.shutdown()
        db.close()
    }

    private fun startBleIfReady() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }
        if (!hasPermissions()) {
            Log.w(TAG, "Permissions missing")
            return
        }
        bleHelper.startScanning()
        updateBroadcastMessage()
    }

    private fun updateBroadcastMessage() {
        val msg = buildBroadcastMessage()
        bleHelper.advertiseMessageLoop(msg)
    }

    private fun buildBroadcastMessage(): String {
        val cursor = db.query(
            MessageDbHelper.TABLE_NAME,
            null, null, null, null, null,
            "${MessageDbHelper.COL_TIMESTAMP} ASC",
            "$maxHistory"
        )
        val messages = mutableListOf<MessageV2>()
        while (cursor.moveToNext()) {
            val sender = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_SENDER))
            val receiver = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_RECEIVER))
            val payload = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_PAYLOAD))
            val ts = cursor.getLong(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_TIMESTAMP))
            messages.add(MessageV2(sender, receiver, payload, ts))
        }
        cursor.close()
        // Use ':' between payload and timestamp
        return messages.joinToString(",") { "${it.sender}|${it.receiver}|${it.payload}:${it.timestamp}" }
    }

//    private fun buildBroadcastMessage(): String {
//        val cursor = db.query(
//            MessageDbHelper.TABLE_NAME,
//            null, null, null, null, null,
//            "${MessageDbHelper.COL_TIMESTAMP} ASC",
//            "$maxHistory"
//        )
//        val messages = mutableListOf<MessageV2>()
//        while (cursor.moveToNext()) {
//            val sender = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_SENDER))
//            val receiver = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_RECEIVER))
//            val payload = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_PAYLOAD))
//            val ts = cursor.getLong(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_TIMESTAMP))
//            messages.add(MessageV2(sender, receiver, payload, ts))
//        }
//        cursor.close()
//        return messages.joinToString(",") { "${it.sender}|${it.receiver}|${it.payload}_${it.timestamp}" }
//    }

//    private fun processReceivedMessage(message: String) {
//        val received = message.split(",")
//        val minTs = 1577836800000L
//        var updated = false
//        for (entry in received) {
//            val parts = entry.split("_")
//            if (parts.size != 2) continue
//            val header = parts[0]
//            val ts = parts[1].toLongOrNull() ?: continue
//            if (ts <= minTs) continue
//            val fields = header.split("|")
//            if (fields.size != 3) continue
//            val sender = fields[0]; val receiver = fields[1]; val payload = fields[2]
//            val m = MessageV2(sender, receiver, payload, ts)
//            if (!messageSet.contains(m.id)) {
//                addMessageToDb(m)
//                if (receiver == "ALL") updated = true
//                else if (receiver == localPublicKey) {
//                    updated = true
//                }
//            }
//        }
//        if (updated) loadMessagesFromDb()
//    }
    private fun processReceivedMessage(message: String) {
        val received = message.split(",")
        val minTs = 1577836800000L
        var updated = false
        for (entry in received) {
            val sepIdx = entry.lastIndexOf(':')
            if (sepIdx <= 0) continue
            val header = entry.substring(0, sepIdx)
            val ts = entry.substring(sepIdx + 1).toLongOrNull() ?: continue
            if (ts <= minTs) continue

            val fields = header.split("|", limit = 3)
            if (fields.size != 3) continue
            val sender = fields[0]; val receiver = fields[1]; val payload = fields[2]

            val m = MessageV2(sender, receiver, payload, ts)
            if (!messageSet.contains(m.id)) {
                addMessageToDb(m)
                if (receiver == "ALL") updated = true
                else if (receiver == localPublicKey) updated = true
            }
        }
        if (updated) loadMessagesFromDb()
    }

    private fun loadLocalKeys() {
        try {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val sp = EncryptedSharedPreferences.create(
                "secure_prefs",
                master,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            localPrivateKey = sp.getString("private_key", null)
            localPublicKey = sp.getString("public_key", null)
        } catch (e: Exception) {
            Log.w(TAG, "loadLocalKeys: ${e.message}")
        }
    }

    private fun loadMessagesFromDb() {
        messageSet.clear()
        val cursor = db.query(MessageDbHelper.TABLE_NAME, null, null, null, null, null, "${MessageDbHelper.COL_TIMESTAMP} ASC")
        val messages = mutableListOf<MessageV2>()
        while (cursor.moveToNext()) {
            val sender = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_SENDER))
            val receiver = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_RECEIVER))
            val payload = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_PAYLOAD))
            val ts = cursor.getLong(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_TIMESTAMP))
            val m = MessageV2(sender, receiver, payload, ts)
            messages.add(m)
            messageSet.add(m.id)
        }
        cursor.close()
        updateMessagesUi(messages)
    }

    private fun updateMessagesUi(messages: List<MessageV2>) {
        runOnUiThread {
            val visible = messages.filter { it.receiver == "ALL" || it.receiver == localPublicKey }
            val formatted = visible.sortedBy { it.timestamp }.joinToString("<br>") { msg ->
                val payloadText = when {
                    msg.receiver == "ALL" -> {
                        try {
                            String(Base64.decode(msg.payload, b64Flags), Charsets.UTF_8)
                        } catch (e: Exception) { "(binary)" }
                    }
                    msg.receiver == localPublicKey -> {
                        try {
                            val lp = localPrivateKey ?: throw IllegalStateException("Missing private key")
                            EncryptionUtil.decryptFrom(msg.sender, lp, msg.payload)
                        } catch (e: Exception) {
                            "(encrypted)"
                        }
                    }
                    else -> "(hidden)"
                }
                "<b>${shortId(msg.sender)} → ${if (msg.receiver == "ALL") "ALL" else "YOU"}:</b> $payloadText <span style='float:right; font-size:smaller; color:#888;'>${dateFormat.format(Date(msg.timestamp))}</span>"
            }
            receiveEditText.setText(Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY))
        }
    }

    private fun addMessageToDb(m: MessageV2) {
        if (messageSet.contains(m.id)) return
        val v = ContentValues().apply {
            put(MessageDbHelper.COL_SENDER, m.sender)
            put(MessageDbHelper.COL_RECEIVER, m.receiver)
            put(MessageDbHelper.COL_PAYLOAD, m.payload)
            put(MessageDbHelper.COL_TIMESTAMP, m.timestamp)
        }
        db.insert(MessageDbHelper.TABLE_NAME, null, v)
        messageSet.add(m.id)
    }

    private fun shortId(id: String): String = if (id.length > 8) id.substring(0, 8) else id

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    data class MessageV2(
        val sender: String,
        val receiver: String,
        val payload: String,
        val timestamp: Long
    ) {
        val id: String
            get() = "$sender:$receiver:$timestamp"
    }
}


//package com.example.abj_chat
//
//import android.Manifest
//import android.bluetooth.BluetoothAdapter
//import android.bluetooth.BluetoothManager
//import android.bluetooth.le.*
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.os.ParcelUuid
//import android.text.Html
//import android.util.Base64
//import android.util.Log
//import android.widget.Button
//import android.widget.EditText
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//import androidx.core.content.ContextCompat
//import androidx.security.crypto.EncryptedSharedPreferences
//import androidx.security.crypto.MasterKeys
//import android.database.sqlite.SQLiteDatabase
//import android.database.sqlite.SQLiteOpenHelper
//import android.content.ContentValues
//import java.text.SimpleDateFormat
//import java.util.*
//import kotlin.collections.HashSet
//import android.view.Menu
//import android.view.MenuItem
//import androidx.appcompat.app.AlertDialog
//import android.content.ClipboardManager
//import android.content.ClipData
//import android.content.Context
//
//class MainActivity : AppCompatActivity() {
//
//    private lateinit var bluetoothAdapter: BluetoothAdapter
//    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
//    private lateinit var bluetoothLeScanner: BluetoothLeScanner
//    private val serviceUuid = UUID.fromString("0000ab11-0000-1000-8000-00805f9b34fb")
//    private val permissions = arrayOf(
//        Manifest.permission.BLUETOOTH_ADVERTISE,
//        Manifest.permission.BLUETOOTH_SCAN,
//        Manifest.permission.BLUETOOTH_CONNECT,
//        Manifest.permission.ACCESS_FINE_LOCATION
//    )
//    private val requestCodePermissions = 1
//
//    private val maxChunkSize = 20
//    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
//    private var currentBroadcastMessage: String? = null
//    private val deviceNamePrefix = "ab"
//    private val maxHistory = 200
//
//    private lateinit var usernameEditText: EditText
//    private lateinit var recipientEditText: EditText
//    private lateinit var sendEditText: EditText
//    private lateinit var receiveEditText: EditText
//
//    private lateinit var dbHelper: MessageDbHelper
//    private lateinit var db: SQLiteDatabase
//    private val messageSet = HashSet<String>() // dedup by id (sender:receiver:timestamp)
//
//    // Persistent chunk reassembly state (unchanged)
//    private val chunkBuffer = mutableMapOf<String, MutableMap<Int, ByteArray>>()
//    private val chunkMeta = mutableMapOf<String, Int>()
//    private val chunkTimestamps = mutableMapOf<String, Long>()
//    private val chunkTimeoutMs = 30_000L
//
//    private val cleanupHandler = Handler(Looper.getMainLooper())
//    private val cleanupRunnable = object : Runnable {
//        override fun run() {
//            val now = System.currentTimeMillis()
//            val expired = chunkTimestamps.filterValues { now - it > chunkTimeoutMs }.keys
//            for (id in expired) {
//                chunkBuffer.remove(id)
//                chunkMeta.remove(id)
//                chunkTimestamps.remove(id)
//            }
//            cleanupHandler.postDelayed(this, chunkTimeoutMs)
//        }
//    }
//
//    private val scanCallback = object : ScanCallback() {
//        override fun onScanResult(callbackType: Int, result: ScanResult) {
//            val scanRecord = result.scanRecord ?: return
//
//            // Prefer service data (must match how we advertise - addServiceData)
//            val serviceData = scanRecord.getServiceData(ParcelUuid(serviceUuid))
//            if (serviceData == null || serviceData.size < 6) {
//                Log.d("BLE", "No or invalid service data found in scan result")
//                return
//            }
//
//            val messageId = String(serviceData.sliceArray(0..3), Charsets.UTF_8)
//            val totalChunks = serviceData[4].toInt() and 0xFF
//            val chunkIndex = serviceData[5].toInt() and 0xFF
//            val chunkData = serviceData.sliceArray(6 until serviceData.size)
//
//            val buffer = chunkBuffer.getOrPut(messageId) { mutableMapOf() }
//            buffer[chunkIndex] = chunkData
//            chunkMeta[messageId] = totalChunks
//            chunkTimestamps[messageId] = System.currentTimeMillis()
//
//            if (buffer.size == totalChunks) {
//                val fullData = (0 until totalChunks).flatMap { idx ->
//                    buffer[idx]?.toList() ?: emptyList()
//                }.toByteArray()
//                val message = String(fullData, Charsets.UTF_8)
//                Log.i("BLE", "Reassembled message: $message")
//                processReceivedMessage(message)
//                chunkBuffer.remove(messageId)
//                chunkMeta.remove(messageId)
//                chunkTimestamps.remove(messageId)
//            }
//        }
//
//        override fun onScanFailed(errorCode: Int) {
//            Log.e("BLE", "Scan failed: $errorCode")
//        }
//    }
//
//    private var advertiseCallback: AdvertiseCallback? = null
//    private var advertiseChunksTimer: Timer? = null
//
//    // local keys (read from secure prefs)
//    private var localPublicKey: String? = null
//    private var localPrivateKey: String? = null
//    private val b64Flags = Base64.URL_SAFE or Base64.NO_WRAP
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        // load local keys
//        loadLocalKeys()
//
//        if (!hasPermissions()) {
//            ActivityCompat.requestPermissions(this, permissions, requestCodePermissions)
//        }
//
//        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
//        bluetoothAdapter = bluetoothManager.adapter
//        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
//        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
//
//        usernameEditText = findViewById(R.id.usernameEditText)
//        recipientEditText = findViewById(R.id.recipientEditText)
//        sendEditText = findViewById(R.id.sendEditText)
//        receiveEditText = findViewById(R.id.receiveEditText)
//        val sendButton = findViewById<Button>(R.id.sendButton)
//
//        dbHelper = MessageDbHelper(this)
//        db = dbHelper.writableDatabase
//
//        // Load all messages from DB at startup
//        loadMessagesFromDb()
//
//        usernameEditText.setOnFocusChangeListener { _, hasFocus ->
//            if (!hasFocus) updateDeviceName()
//        }
//        usernameEditText.setOnEditorActionListener { _, _, _ ->
//            updateDeviceName()
//            false
//        }
//
//        sendButton.setOnClickListener {
//            val username = usernameEditText.text.toString().trim()
//            if (username.isBlank()) {
//                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
//                return@setOnClickListener
//            }
//            updateDeviceName()
//            val messageText = sendEditText.text.toString().trim()
//            if (messageText.isNotBlank()) {
//                val recipient = recipientEditText.text.toString().trim().ifBlank { "ALL" }
//                val timestamp = System.currentTimeMillis()
//                val senderId = localPublicKey ?: username // fallback
//
//                val payloadB64 = if (recipient == "ALL") {
//                    Base64.encodeToString(messageText.toByteArray(Charsets.UTF_8), b64Flags)
//                } else {
//                    // Directed: encrypt for recipient public key (recipient input must be recipient's public key)
//                    try {
//                        val localPriv = localPrivateKey ?: throw IllegalStateException("Local private key missing")
//                        EncryptionUtil.encryptFor(recipient, localPriv, messageText)
//                    } catch (e: Exception) {
//                        Toast.makeText(this, "Encryption failed: ${e.message}", Toast.LENGTH_SHORT).show()
//                        return@setOnClickListener
//                    }
//                }
//
//                val msg = MessageV2(senderId, recipient, payloadB64, timestamp)
//                addMessageToDb(msg)
//
//                // Immediately refresh UI so the sent message appears right away
//                loadMessagesFromDb()
//
//                // Update what we broadcast to neighbors
//                updateBroadcastMessage()
//                Toast.makeText(this, "Message queued for broadcast", Toast.LENGTH_SHORT).show()
//                sendEditText.setText("")
//            } else {
//                Toast.makeText(this, "Enter a message to send", Toast.LENGTH_SHORT).show()
//            }
//        }
////        sendButton.setOnClickListener {
////            val username = usernameEditText.text.toString().trim()
////            if (username.isBlank()) {
////                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
////                return@setOnClickListener
////            }
////            updateDeviceName()
////            val messageText = sendEditText.text.toString().trim()
////            if (messageText.isNotBlank()) {
////                val recipient = recipientEditText.text.toString().trim().ifBlank { "ALL" }
////                val timestamp = System.currentTimeMillis()
////                val senderId = localPublicKey ?: username // fallback
////
////                val payloadB64 = if (recipient == "ALL") {
////                    Base64.encodeToString(messageText.toByteArray(Charsets.UTF_8), b64Flags)
////                } else {
////                    // Directed: encrypt for recipient public key (recipient input must be recipient's public key)
////                    try {
////                        val localPriv = localPrivateKey ?: throw IllegalStateException("Local private key missing")
////                        EncryptionUtil.encryptFor(recipient, localPriv, messageText)
////                    } catch (e: Exception) {
////                        Toast.makeText(this, "Encryption failed: ${e.message}", Toast.LENGTH_SHORT).show()
////                        return@setOnClickListener
////                    }
////                }
////
////                val msg = MessageV2(senderId, recipient, payloadB64, timestamp)
////                addMessageToDb(msg)
////                updateBroadcastMessage()
////                Toast.makeText(this, "Message queued for broadcast", Toast.LENGTH_SHORT).show()
////                sendEditText.setText("")
////            } else {
////                Toast.makeText(this, "Enter a message to send", Toast.LENGTH_SHORT).show()
////            }
////        }
//
////        sendButton.setOnClickListener {
////            val username = usernameEditText.text.toString().trim()
////            if (username.isBlank()) {
////                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
////                return@setOnClickListener
////            }
////            updateDeviceName()
////            val messageText = sendEditText.text.toString().trim()
////            if (messageText.isNotBlank()) {
////                val recipient = recipientEditText.text.toString().trim().ifBlank { "ALL" }
////                val timestamp = System.currentTimeMillis()
////                val senderId = localPublicKey ?: username // fallback
////                // payload encoding: URL_SAFE base64 to avoid delimiter collisions
////                val payloadB64 = Base64.encodeToString(messageText.toByteArray(Charsets.UTF_8), b64Flags)
////                val msg = MessageV2(senderId, recipient, payloadB64, timestamp)
////                addMessageToDb(msg)
////                updateBroadcastMessage()
////                Toast.makeText(this, "Message queued for broadcast", Toast.LENGTH_SHORT).show()
////                sendEditText.setText("")
////            } else {
////                Toast.makeText(this, "Enter a message to send", Toast.LENGTH_SHORT).show()
////            }
////        }
//
//        cleanupHandler.postDelayed(cleanupRunnable, chunkTimeoutMs)
//        startScanning()
//        updateBroadcastMessage()
//    }
//
////    private fun updateMessagesUi(messages: List<MessageV2>) {
////        runOnUiThread {
////            val visible = messages.filter { it.receiver == "ALL" || it.receiver == localPublicKey }
////            val formatted = visible.sortedBy { it.timestamp }.joinToString("<br>") {
////                val displayPayload = when {
////                    it.receiver == "ALL" -> {
////                        try {
////                            String(Base64.decode(it.payload, b64Flags), Charsets.UTF_8)
////                        } catch (e: Exception) { "(binary)" }
////                    }
////                    it.receiver == localPublicKey -> {
////                        // Attempt decrypt using our private key and sender public key
////                        try {
////                            val lp = localPrivateKey ?: throw IllegalStateException("Missing private key")
////                            EncryptionUtil.decryptFrom(it.sender, lp, it.payload)
////                        } catch (e: Exception) {
////                            "(encrypted)"
////                        }
////                    }
////                    else -> "(hidden)"
////                }
////                "<b>${shortId(it.sender)} → ${if (it.receiver == "ALL") "ALL" else "YOU"}:</b> ${displayPayload} <span style='float:right; font-size:smaller; color:#888;'>${dateFormat.format(Date(it.timestamp))}</span>"
////            }
////            receiveEditText.setText(Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY))
////        }
////    }
//
//    private fun loadLocalKeys() {
//        try {
//            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
//            val sp = EncryptedSharedPreferences.create(
//                "secure_prefs",
//                masterKeyAlias,
//                this,
//                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
//                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
//            )
//            localPrivateKey = sp.getString("private_key", null)
//            localPublicKey = sp.getString("public_key", null)
//        } catch (e: Exception) {
//            Log.w("KEYS", "Unable to load local keys: ${e.message}")
//        }
//    }
//
//    private fun hasPermissions(): Boolean {
//        return permissions.all {
//            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
//        }
//    }
//
//    private fun updateBroadcastMessage() {
//        val broadcastMessage = buildBroadcastMessage()
//        if (broadcastMessage != currentBroadcastMessage) {
//            currentBroadcastMessage = broadcastMessage
//            startAdvertising(broadcastMessage)
//        }
//    }
//
//    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
//        menuInflater.inflate(R.menu.main_menu, menu)
//        return true
//    }
//
//    override fun onOptionsItemSelected(item: MenuItem): Boolean {
//        return when (item.itemId) {
//            R.id.action_show_public_key -> {
//                showPublicKeyDialog()
//                true
//            }
//            else -> super.onOptionsItemSelected(item)
//        }
//    }
//
//    private fun showPublicKeyDialog() {
//        val pk = localPublicKey ?: "(not set)"
//        val builder = AlertDialog.Builder(this)
//            .setTitle("Your public key")
//            .setMessage(pk)
//            .setPositiveButton("Copy") { _, _ ->
//                copyToClipboard("public_key", pk)
//                Toast.makeText(this, "Public key copied", Toast.LENGTH_SHORT).show()
//            }
//            .setNeutralButton("Close", null)
//        builder.show()
//    }
//
//    private fun copyToClipboard(label: String, text: String) {
//        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
//        val clip = ClipData.newPlainText(label, text)
//        clipboard.setPrimaryClip(clip)
//    }
//
//    private fun buildBroadcastMessage(): String {
//        // broadcast all recent messages from DB
//        val cursor = db.query(
//            MessageDbHelper.TABLE_NAME,
//            null, null, null, null, null,
//            "${MessageDbHelper.COL_TIMESTAMP} ASC",
//            "${maxHistory}" // limit
//        )
//        val messages = mutableListOf<MessageV2>()
//        while (cursor.moveToNext()) {
//            val sender = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_SENDER))
//            val receiver = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_RECEIVER))
//            val payload = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_PAYLOAD))
//            val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_TIMESTAMP))
//            messages.add(MessageV2(sender, receiver, payload, timestamp))
//        }
//        cursor.close()
//        // serialize each message as sender|receiver|payloadBase64_timestamp
//        return messages.joinToString(",") { "${it.sender}|${it.receiver}|${it.payload}_${it.timestamp}" }
//    }
//
//    private fun startAdvertising(broadcastMessage: String) {
//        stopAdvertising()
//        val chunks = splitMessageIntoChunks(broadcastMessage)
//        advertiseChunksLoop(chunks)
//    }
//
//    private fun stopAdvertising() {
//        advertiseChunksTimer?.cancel()
//        advertiseChunksTimer = null
//        if (::bluetoothLeAdvertiser.isInitialized && advertiseCallback != null) {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
//                Log.w("BLE", "Missing BLUETOOTH_ADVERTISE permission - cannot stop advertising")
//                return
//            }
//            try {
//                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
//            } catch (se: SecurityException) {
//                Log.e("BLE", "SecurityException stopping advertising: ${se.message}")
//            } catch (e: Exception) {
//                Log.e("BLE", "Error stopping advertising: ${e.message}")
//            } finally {
//                advertiseCallback = null
//            }
//        }
//    }
//
//    // message chunk format (short ids): [msgId(4)][total(1)][index(1)][data...]
//    private fun splitMessageIntoChunks(message: String): List<ByteArray> {
//        val headerSize = 4 + 1 + 1
//        val maxPayload = maxChunkSize - headerSize
//        val dataBytes = message.toByteArray(Charsets.UTF_8)
//        val totalChunks = (dataBytes.size + maxPayload - 1) / maxPayload
//        val messageId = UUID.randomUUID().toString().substring(0, 4)
//        val chunks = mutableListOf<ByteArray>()
//        for (i in 0 until totalChunks) {
//            val start = i * maxPayload
//            val end = minOf(start + maxPayload, dataBytes.size)
//            val chunkData = dataBytes.sliceArray(start until end)
//            val header = messageId.toByteArray(Charsets.UTF_8)
//            val meta = byteArrayOf(totalChunks.toByte(), i.toByte())
//            val chunk = header + meta + chunkData
//            if (chunk.size > maxChunkSize) {
//                Log.e("BLE", "Chunk size ${chunk.size} exceeds max $maxChunkSize bytes, dropping chunk!")
//                continue
//            }
//            chunks.add(chunk)
//        }
//        return chunks
//    }
//
////    private fun advertiseChunksLoop(chunks: List<ByteArray>) {
////        advertiseChunksTimer?.cancel()
////        if (chunks.isEmpty()) return
////
////        val settings = AdvertiseSettings.Builder()
////            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
////            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
////            .setConnectable(false)
////            .build()
////
////        var chunkIndex = 0
////        advertiseCallback = object : AdvertiseCallback() {
////            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
////                Log.i("BLE", "Advertising chunk ${chunkIndex + 1}/${chunks.size}")
////            }
////
////            override fun onStartFailure(errorCode: Int) {
////                Log.e("BLE", "Advertising failed: $errorCode")
////            }
////        }
////
////        advertiseChunksTimer = Timer()
////        advertiseChunksTimer?.scheduleAtFixedRate(object : TimerTask() {
////            override fun run() {
////                try {
////                    bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
////                } catch (_: Exception) {}
////                val data = AdvertiseData.Builder()
////                    .setIncludeDeviceName(false)
////                    .addServiceUuid(ParcelUuid(serviceUuid))
////                    .addManufacturerData(0, chunks[chunkIndex])
////                    .build()
////                bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
////                chunkIndex = (chunkIndex + 1) % chunks.size
////            }
////        }, 0, 350)
////    }
//
//    private fun advertiseChunksLoop(chunks: List<ByteArray>) {
//        advertiseChunksTimer?.cancel()
//        if (chunks.isEmpty()) return
//
//        val settings = AdvertiseSettings.Builder()
//            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
//            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
//            .setConnectable(false)
//            .build()
//
//        var chunkIndex = 0
//        advertiseCallback = object : AdvertiseCallback() {
//            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
//                Log.i("BLE", "Advertise started")
//            }
//
//            override fun onStartFailure(errorCode: Int) {
//                Log.e("BLE", "Advertise start failed: $errorCode")
//            }
//        }
//
//        val mainHandler = Handler(Looper.getMainLooper())
//        advertiseChunksTimer = Timer()
//        advertiseChunksTimer?.scheduleAtFixedRate(object : TimerTask() {
//            override fun run() {
//                // build next chunk
//                val chunk = chunks[chunkIndex]
//                chunkIndex = (chunkIndex + 1) % chunks.size
//
//                // permission check
//                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
//                    Log.w("BLE", "Missing BLUETOOTH_ADVERTISE permission - skipping advertise")
//                    return
//                }
//
//                // Post advertise calls to main thread
//                mainHandler.post {
//                    try {
//                        // stop any existing advertise first
//                        try {
//                            if (::bluetoothLeAdvertiser.isInitialized) {
//                                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
//                            }
//                        } catch (ignored: Exception) { /* ignore stop errors */ }
//
//                        val data = AdvertiseData.Builder()
//                            .setIncludeDeviceName(false)
//                            .setIncludeTxPowerLevel(false)
//                            .addServiceData(ParcelUuid(serviceUuid), chunk)
//                            .build()
//
//                        bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
//                    } catch (se: SecurityException) {
//                        Log.e("BLE", "SecurityException starting advertising: ${se.message}")
//                    } catch (e: Exception) {
//                        Log.e("BLE", "Error starting advertising: ${e.message}")
//                    }
//                }
//            }
//        }, 0, 350)
//    }
//
//
//    override fun onDestroy() {
//        super.onDestroy()
//        advertiseChunksTimer?.cancel()
//        stopAdvertising()
//        cleanupHandler.removeCallbacks(cleanupRunnable)
//    }
//
////    private fun startScanning() {
////        if (!bluetoothAdapter.isEnabled || !hasPermissions() || !::bluetoothLeScanner.isInitialized) {
////            Log.e("BLE", "Cannot start scanning: Bluetooth not enabled or permissions missing.")
////            return
////        }
////
////        val scanSettings = ScanSettings.Builder()
////            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
////            .build()
////
////        val scanFilter = ScanFilter.Builder()
////            .setServiceUuid(ParcelUuid(serviceUuid))
////            .build()
////
////        try {
////            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
////        } catch (e: SecurityException) {
////            Log.e("BLE", "SecurityException: ${e.message}")
////        }
////    }
//    // kotlin
//    private fun startScanning() {
//        if (!::bluetoothAdapter.isInitialized) {
//            Log.e("BLE", "BluetoothAdapter not initialized")
//            return
//        }
//        if (!bluetoothAdapter.isEnabled) {
//            Log.e("BLE", "Bluetooth is disabled")
//            return
//        }
//        if (!hasPermissions()) {
//            Log.e("BLE", "Missing required BLE permissions")
//            return
//        }
//        if (!::bluetoothLeScanner.isInitialized) {
//            Log.e("BLE", "BluetoothLeScanner not initialized")
//            return
//        }
//
//        val scanSettings = ScanSettings.Builder()
//            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//            .build()
//
//        try {
//            // ensure any previous scan is stopped first
//            try {
//                bluetoothLeScanner.stopScan(scanCallback)
//            } catch (ignored: Exception) { /* ignore */ }
//
//            // Start a full scan (no ScanFilter) so service-data-only adverts are received.
//            bluetoothLeScanner.startScan(null, scanSettings, scanCallback)
//            Log.i("BLE", "Started scanning (no filters) to receive service-data adverts")
//        } catch (se: SecurityException) {
//            Log.e("BLE", "SecurityException starting scan: ${se.message}")
//        } catch (e: Exception) {
//            Log.e("BLE", "Failed to start scan: ${e.message}")
//        }
//    }
//
//    // kotlin
//    // File: `app/src/main/java/com/example/abj_chat/MainActivity.kt`
//    private fun processReceivedMessage(message: String) {
//        // messages serialized as: sender|receiver|payloadBase64_timestamp , multiple separated by commas
//        val receivedMessagesRaw = message.split(",")
//        val minValidTimestamp = 1577836800000L // 2020-01-01
//        var isUpdated = false
//        for (entry in receivedMessagesRaw) {
//            val parts = entry.split("_")
//            if (parts.size != 2) continue
//            val payloadPart = parts[0] // sender|receiver|payloadB64
//            val timestamp = parts[1].toLongOrNull() ?: continue
//            if (timestamp <= minValidTimestamp) continue
//
//            val headerParts = payloadPart.split("|")
//            if (headerParts.size != 3) continue
//            val sender = headerParts[0]
//            val receiver = headerParts[1]
//            val payloadB64 = headerParts[2]
//
//            val newMessage = MessageV2(sender, receiver, payloadB64, timestamp)
//            val id = newMessage.id
//            if (!messageSet.contains(id)) {
//                // Store all messages for forwarding.
//                addMessageToDb(newMessage)
//
//                // Decide whether to refresh UI: always for broadcasts, for directed try decrypt locally.
//                if (receiver == "ALL") {
//                    isUpdated = true
//                } else {
//                    // Attempt local decrypt to see if this message is for this node.
//                    val localPriv = localPrivateKey
//                    if (localPriv != null) {
//                        try {
//                            // If decrypt succeeds, mark updated so UI will show the plaintext
//                            EncryptionUtil.decryptFrom(sender, localPriv, payloadB64)
//                            isUpdated = true
//                        } catch (_: Exception) {
//                            // decryption failed -> not for us (or malformed) -> do not mark updated
//                        }
//                    }
//                }
//            }
//        }
//        if (isUpdated) loadMessagesFromDb()
//    }
//
//    private fun updateMessagesUi(messages: List<MessageV2>) {
//        runOnUiThread {
//            // Show broadcasts and directed messages; directed messages will display plaintext only if local decrypt succeeds.
//            val formatted = messages.sortedBy { it.timestamp }.joinToString("<br>") { msg ->
//                val displayPayload = if (msg.receiver == "ALL") {
//                    try {
//                        String(Base64.decode(msg.payload, b64Flags), Charsets.UTF_8)
//                    } catch (e: Exception) {
//                        "(binary)"
//                    }
//                } else {
//                    // Directed: every node attempts to decrypt with its own private key.
//                    val localPriv = localPrivateKey
//                    if (localPriv == null) {
//                        "(encrypted)"
//                    } else {
//                        try {
//                            EncryptionUtil.decryptFrom(msg.sender, localPriv, msg.payload)
//                        } catch (e: Exception) {
//                            "(encrypted)"
//                        }
//                    }
//                }
//
//                "<b>${shortId(msg.sender)} → ${if (msg.receiver == "ALL") "ALL" else "YOU"}:</b> ${displayPayload} <span style='float:right; font-size:smaller; color:#888;'>${dateFormat.format(Date(msg.timestamp))}</span>"
//            }
//            receiveEditText.setText(Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY))
//        }
//    }
////    private fun processReceivedMessage(message: String) {
////        // messages serialized as: sender|receiver|payloadBase64_timestamp , multiple separated by commas
////        val receivedMessagesRaw = message.split(",")
////        val minValidTimestamp = 1577836800000L // 2020-01-01
////        var isUpdated = false
////        for (entry in receivedMessagesRaw) {
////            val parts = entry.split("_")
////            if (parts.size != 2) continue
////            val payloadPart = parts[0] // sender|receiver|payloadB64
////            val timestamp = parts[1].toLongOrNull() ?: continue
////            if (timestamp <= minValidTimestamp) continue
////
////            val headerParts = payloadPart.split("|")
////            if (headerParts.size != 3) continue
////            val sender = headerParts[0]
////            val receiver = headerParts[1]
////            val payloadB64 = headerParts[2]
////
////            val newMessage = MessageV2(sender, receiver, payloadB64, timestamp)
////            val id = newMessage.id
////            if (!messageSet.contains(id)) {
////                // Store all messages for forwarding. Display only if addressed to ALL or to me.
////                addMessageToDb(newMessage)
////                // Only mark updated/displayed if the message is for ALL or for this node
////                if (receiver == "ALL" || receiver == localPublicKey) {
////                    isUpdated = true
////                }
////            }
////        }
////        if (isUpdated) loadMessagesFromDb()
////    }
//
//    private fun loadMessagesFromDb() {
//        messageSet.clear()
//        val cursor = db.query(
//            MessageDbHelper.TABLE_NAME,
//            null, null, null, null, null,
//            "${MessageDbHelper.COL_TIMESTAMP} ASC"
//        )
//        val messages = mutableListOf<MessageV2>()
//        while (cursor.moveToNext()) {
//            val sender = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_SENDER))
//            val receiver = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_RECEIVER))
//            val payload = cursor.getString(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_PAYLOAD))
//            val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(MessageDbHelper.COL_TIMESTAMP))
//            val m = MessageV2(sender, receiver, payload, timestamp)
//            messages.add(m)
//            messageSet.add(m.id)
//        }
//        cursor.close()
//        updateMessagesUi(messages)
//    }
//
//    private fun addMessageToDb(message: MessageV2) {
//        if (messageSet.contains(message.id)) return
//        val values = ContentValues().apply {
//            put(MessageDbHelper.COL_SENDER, message.sender)
//            put(MessageDbHelper.COL_RECEIVER, message.receiver)
//            put(MessageDbHelper.COL_PAYLOAD, message.payload)
//            put(MessageDbHelper.COL_TIMESTAMP, message.timestamp)
//        }
//        db.insert(MessageDbHelper.TABLE_NAME, null, values)
//        messageSet.add(message.id)
//        // Do not reload DB here for performance; caller may call loadMessagesFromDb()
//    }
//
////    private fun updateMessagesUi(messages: List<MessageV2>) {
////        runOnUiThread {
////            // Show only messages addressed to ALL or to localPublicKey
////            val visible = messages.filter { it.receiver == "ALL" || it.receiver == localPublicKey }
////            val formatted = visible.sortedBy { it.timestamp }.joinToString("<br>") {
////                val payload = try {
////                    String(Base64.decode(it.payload, b64Flags), Charsets.UTF_8)
////                } catch (e: Exception) {
////                    "(binary)"
////                }
////                "<b>${shortId(it.sender)} → ${if (it.receiver == "ALL") "ALL" else "YOU"}:</b> ${payload} <span style='float:right; font-size:smaller; color:#888;'>${dateFormat.format(Date(it.timestamp))}</span>"
////            }
////            receiveEditText.setText(Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY))
////        }
////    }
//
//    private fun shortId(id: String): String {
//        return if (id.length > 8) id.substring(0, 8) else id
//    }
//
//    private fun updateDeviceName() {
//        val username = usernameEditText.text.toString().trim()
//        if (username.isNotBlank()) {
//            // Setting device name requires BLUETOOTH_CONNECT on newer Android versions
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
//                Log.w("BLE", "Missing BLUETOOTH_CONNECT permission - cannot change adapter name")
//                return
//            }
//            try {
//                bluetoothAdapter.name = "$deviceNamePrefix$username"
//            } catch (se: SecurityException) {
//                Log.e("BLE", "SecurityException changing device name: ${se.message}")
//            } catch (e: Exception) {
//                Log.e("BLE", "Error changing device name: ${e.message}")
//            }
//        }
//    }
//
//    data class MessageV2(
//        val sender: String,
//        val receiver: String,
//        val payload: String, // base64 url safe
//        val timestamp: Long
//    ) {
//        val id: String
//            get() = "$sender:$receiver:$timestamp"
//    }
//
//
//    override fun onResume() {
//        super.onResume()
//        // reload keys (may have changed if returned from Login)
//        loadLocalKeys()
//        // ensure bluetooth objects are available and permissions checked
//        ensureBleInitialized()
//        // If permissions already granted and bluetooth enabled, start scanning and advertising
//        if (hasPermissions() && bluetoothAdapter.isEnabled) {
//            try {
//                startScanning()
//                updateBroadcastMessage()
//            } catch (e: Exception) {
//                Log.w("BLE", "Failed to (re)start scanning/advertising on resume: ${e.message}")
//            }
//        } else {
//            // Request missing permissions as before
//            if (!hasPermissions()) {
//                ActivityCompat.requestPermissions(this, permissions, requestCodePermissions)
//            }
//        }
//    }
//
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (requestCode == requestCodePermissions) {
//            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
//            if (allGranted) {
//                Log.i("PERM", "All permissions granted - starting BLE")
//                ensureBleInitialized()
//                try {
//                    startScanning()
//                    updateBroadcastMessage()
//                } catch (e: SecurityException) {
//                    Log.e("PERM", "SecurityException starting BLE after permission grant: ${e.message}")
//                } catch (e: Exception) {
//                    Log.e("PERM", "Exception starting BLE after permission grant: ${e.message}")
//                }
//            } else {
//                Log.w("PERM", "Required permissions not granted")
//                Toast.makeText(this, "BLE permissions required for messaging", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private fun ensureBleInitialized() {
//        try {
//            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
//            if (!::bluetoothAdapter.isInitialized || bluetoothAdapter != bluetoothManager.adapter) {
//                bluetoothAdapter = bluetoothManager.adapter
//            }
//            if (!::bluetoothLeAdvertiser.isInitialized) {
//                // may be null on some devices; guard usage elsewhere
//                bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
//            }
//            if (!::bluetoothLeScanner.isInitialized) {
//                bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
//            }
//        } catch (e: Exception) {
//            Log.w("BLE", "BLE init failed: ${e.message}")
//        }
//    }
//}
