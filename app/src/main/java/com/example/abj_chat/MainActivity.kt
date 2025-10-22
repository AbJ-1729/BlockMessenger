package com.example.abj_chat

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.UUID
import kotlin.collections.HashSet

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val serviceUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val b64Flags = Base64.URL_SAFE or Base64.NO_WRAP

    private lateinit var usernameEditText: EditText
    private lateinit var recipientEditText: EditText
    private lateinit var sendEditText: EditText
    private lateinit var receiveEditText: EditText
    private lateinit var sendButton: Button

    private var privateKey: String? = null
    private var publicKey: String? = null
    private var ble: BleMeshManager? = null
    private lateinit var db: SQLiteDatabase
    private val seenIds = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usernameEditText = findViewById(R.id.usernameEditText)
        recipientEditText = findViewById(R.id.recipientEditText)
        sendEditText = findViewById(R.id.sendEditText)
        receiveEditText = findViewById(R.id.receiveEditText)
        sendButton = findViewById(R.id.sendButton)

        db = MessageDbHelper(this).writableDatabase
        loadKeys()

        ble = BleMeshManager(this, serviceUuid) { serialized ->
            Log.d(TAG, "Incoming serialized=${serialized.take(60)}")
            runOnUiThread { handleIncoming(serialized) }
        }

        sendButton.setOnClickListener { sendMessage() }
        ensurePermissions()
    }

    private fun ensurePermissions() {
        if (PermissionHelper.hasPermissions(this)) onPermissionsGranted()
        else PermissionHelper.requestPermissions(this)
    }

    private fun onPermissionsGranted() {
        ensureBluetoothEnabled()
        ble?.startScan()
    }

    private fun ensureBluetoothEnabled() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Enable Bluetooth manually", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != PermissionHelper.REQUEST_CODE_PERMISSIONS) return
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) onPermissionsGranted()
        else AlertDialog.Builder(this)
            .setTitle("Permissions required")
            .setMessage("Bluetooth permissions are required.")
            .setPositiveButton("Settings") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }.setNegativeButton("Close", null).show()
    }

    private fun loadKeys() {
        try {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs = EncryptedSharedPreferences.create(
                "secure_prefs",
                master,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            privateKey = prefs.getString("private_key", null)
            publicKey = prefs.getString("public_key", null)
            if (privateKey == null || publicKey == null) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish(); return
            }
            usernameEditText.setText(short(publicKey!!))
        } catch (e: Exception) {
            Log.e(TAG, "loadKeys: ${e.message}")
            Toast.makeText(this, "Key load error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage() {
        val senderPub = publicKey ?: return
        val priv = privateKey ?: return
        val text = sendEditText.text.toString().trim()
        if (text.isBlank()) return
        val recRaw = recipientEditText.text.toString().trim()
        val recipient = if (recRaw.isBlank() || recRaw.equals("ALL", true)) "ALL" else recRaw
        val ts = System.currentTimeMillis()

        val (flag, payloadB64, decryptedPlain) = if (recipient == "ALL") {
            val plainB64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), b64Flags)
            Triple("B", plainB64, text)
        } else {
            try {
                val enc = CryptoUtils.encrypt(priv, recipient, text)
                Triple("P", enc, text)
            } catch (e: Exception) {
                Toast.makeText(this, "Encrypt fail: ${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val serialized = listOf("1", senderPub, recipient, flag, ts.toString(), payloadB64).joinToString("|")
        val id = "1|$senderPub|$recipient|$ts"
        seenIds.add(id)
        Log.d(TAG, "Send serialized len=${serialized.length}")
        store(serialized, decryptedPlain)
        enqueue(serialized)
        sendEditText.setText("")
    }

    private fun canAdvertise(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun enqueue(serialized: String) {
        if (!canAdvertise()) {
            Log.w(TAG, "No advertise permission")
            return
        }
        try {
            ble?.enqueueMessage(serialized)
            Log.d(TAG, "Enqueued for BLE rotation")
        } catch (e: Exception) {
            Log.w(TAG, "enqueue fail: ${e.message}")
        }
    }

    private fun handleIncoming(serialized: String) {
        val parts = serialized.split("|")
        if (parts.size < 6) return
        if (parts[0] != "1") return
        val sender = parts[1]
        val receiver = parts[2]
        val flag = parts[3]
        val ts = parts[4].toLongOrNull() ?: return
        val payloadB64 = parts.subList(5, parts.size).joinToString("|")
        val id = "1|$sender|$receiver|$ts"
        if (!seenIds.add(id)) return
        var decrypted: String? = null
        when (flag) {
            "B" -> decrypted = try {
                String(Base64.decode(payloadB64, b64Flags), Charsets.UTF_8)
            } catch (_: Exception) { null }
            "P" -> {
                val myPub = publicKey
                val myPriv = privateKey
                if (myPub != null && myPriv != null && (receiver == myPub || sender == myPub)) {
                    decrypted = try { CryptoUtils.decrypt(myPriv, sender, payloadB64) }
                    catch (e: Exception) { Log.w(TAG, "decrypt fail: ${e.message}"); null }
                }
            }
            else -> return
        }
        store(serialized, decrypted)
        enqueue(serialized)
    }

    private fun store(serialized: String, decrypted: String?) {
        val p = serialized.split("|")
        if (p.size < 6) return
        val version = p[0]
        val sender = p[1]
        val receiver = p[2]
        val flag = p[3]
        val ts = p[4].toLongOrNull() ?: return
        val payload = p.subList(5, p.size).joinToString("|")
        val msgId = "$version|$sender|$receiver|$ts"

        val cv = ContentValues().apply {
            put(MessageDbHelper.COL_ID, msgId)
            put(MessageDbHelper.COL_SENDER, sender)
            put(MessageDbHelper.COL_RECEIVER, receiver)
            put(MessageDbHelper.COL_FLAG, flag)
            put(MessageDbHelper.COL_PAYLOAD, payload)
            put(MessageDbHelper.COL_DECRYPTED, decrypted)
            put(MessageDbHelper.COL_TIMESTAMP, ts)
        }
        try {
            db.insertWithOnConflict(
                MessageDbHelper.TABLE,
                null,
                cv,
                SQLiteDatabase.CONFLICT_IGNORE
            )
        } catch (e: Exception) {
            Log.w(TAG, "DB insert: ${e.message}")
        }
        refreshUi()
    }

    private fun refreshUi() {
        val c = db.query(
            MessageDbHelper.TABLE, null,
            null, null, null, null,
            "${MessageDbHelper.COL_TIMESTAMP} ASC"
        )
        val sb = StringBuilder()
        try {
            while (c.moveToNext()) {
                val sender = c.getString(c.getColumnIndexOrThrow(MessageDbHelper.COL_SENDER))
                val receiver = c.getString(c.getColumnIndexOrThrow(MessageDbHelper.COL_RECEIVER))
                val flag = c.getString(c.getColumnIndexOrThrow(MessageDbHelper.COL_FLAG))
                val ts = c.getLong(c.getColumnIndexOrThrow(MessageDbHelper.COL_TIMESTAMP))
                val raw = c.getString(c.getColumnIndexOrThrow(MessageDbHelper.COL_PAYLOAD))
                val dec = c.getString(c.getColumnIndexOrThrow(MessageDbHelper.COL_DECRYPTED))
                val body = when {
                    dec != null -> escape(dec)
                    flag == "B" -> escape(
                        try { String(Base64.decode(raw, b64Flags), Charsets.UTF_8) }
                        catch (_: Exception) { raw }
                    )
                    else -> "(encrypted)"
                }
                val scope = if (receiver == "ALL") "ALL" else "PRIV"
                val timeShort = ts.toString().takeLast(6)
                sb.append("<b>${short(sender)}</b> → ${short(receiver)} [$scope/$flag $timeShort]: $body<br/>")
            }
        } finally { c.close() }
        receiveEditText.setText(Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_LEGACY))
    }

    private fun short(s: String) = if (s.length > 10) s.substring(0, 10) else s
    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun onResume() {
        super.onResume()
        loadKeys()
        if (PermissionHelper.hasPermissions(this)) {
            ensureBluetoothEnabled()
            ble?.startScan()
        }
    }

    override fun onPause() {
        super.onPause()
        ble?.stopAllBroadcasts()
        ble?.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        ble?.shutdown()
        try { db.close() } catch (_: Exception) {}
    }
}