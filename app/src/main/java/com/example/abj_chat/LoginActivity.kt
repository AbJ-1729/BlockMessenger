// kotlin
            package com.example.abj_chat

            import android.content.ClipData
            import android.content.ClipboardManager
            import android.content.Context
            import android.content.Intent
            import android.os.Bundle
            import android.util.Base64
            import android.widget.*
            import androidx.appcompat.app.AlertDialog
            import androidx.appcompat.app.AppCompatActivity
            import androidx.security.crypto.EncryptedSharedPreferences
            import androidx.security.crypto.MasterKeys
            import com.google.firebase.firestore.FirebaseFirestore
            import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
            import java.security.SecureRandom

            class LoginActivity : AppCompatActivity() {

                private lateinit var privateKeyEditText: EditText
                private lateinit var loginButton: Button
                private lateinit var registerButton: Button
                private lateinit var instructionsTextView: TextView
                private val firestore = FirebaseFirestore.getInstance()
                private val b64Flags = Base64.URL_SAFE or Base64.NO_WRAP

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_login)

                    privateKeyEditText = findViewById(R.id.privateKeyEditText)
                    loginButton = findViewById(R.id.loginButton)
                    registerButton = findViewById(R.id.registerButton)
                    instructionsTextView = findViewById(R.id.instructionsTextView)

                    loginButton.setOnClickListener { login() }
                    registerButton.setOnClickListener { register() }
                }

                private fun register() {
                    try {
                        val secureRandom = SecureRandom()
                        val priv = X25519PrivateKeyParameters(secureRandom)
                        val pub = priv.generatePublicKey()

                        val publicKeyB64 = Base64.encodeToString(pub.encoded, b64Flags)
                        val privateKeyB64 = Base64.encodeToString(priv.encoded, b64Flags)

                        firestore.collection("users").document(publicKeyB64).set(mapOf("registered" to true))
                            .addOnSuccessListener {
                                cacheKeys(privateKeyB64, publicKeyB64)
                                instructionsTextView.text = "Keys generated. Tap \"View Keys\" to copy."
                                showKeysDialog(publicKeyB64, privateKeyB64)
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Registration failed: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Key generation error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                private fun login() {
                    val privateKey = privateKeyEditText.text.toString().trim()
                    if (privateKey.isBlank()) {
                        Toast.makeText(this, "Please enter your private key", Toast.LENGTH_SHORT).show()
                        return
                    }

                    val publicKey = try {
                        derivePublicKey(privateKey)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Invalid private key: ${e.message}", Toast.LENGTH_SHORT).show()
                        return
                    }

                    firestore.collection("users").document(publicKey).get()
                        .addOnSuccessListener { document ->
                            if (document.exists()) {
                                cacheKeys(privateKey, publicKey)
                                startActivity(Intent(this, MainActivity::class.java))
                                finish()
                            } else {
                                Toast.makeText(this, "Public key not found. Please register.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Login failed: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                }

                private fun derivePublicKey(privateKeyB64: String): String {
                    val privBytes = Base64.decode(privateKeyB64, b64Flags)
                    if (privBytes.isEmpty()) throw IllegalArgumentException("Decoded private key is empty")
                    val priv = X25519PrivateKeyParameters(privBytes, 0)
                    val pub = priv.generatePublicKey()
                    return Base64.encodeToString(pub.encoded, b64Flags)
                }

                private fun cacheKeys(privateKey: String, publicKey: String) {
                    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                    val sharedPreferences = EncryptedSharedPreferences.create(
                        "secure_prefs",
                        masterKeyAlias,
                        this,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    sharedPreferences.edit().putString("private_key", privateKey).putString("public_key", publicKey).apply()
                }

                private fun showKeysDialog(publicKey: String, privateKey: String) {
                    val message = "Public Key:\n$publicKey\n\nPrivate Key:\n$privateKey"
                    val builder = AlertDialog.Builder(this)
                        .setTitle("Generated Keys")
                        .setMessage(message)
                        .setPositiveButton("Copy Public") { _, _ ->
                            copyToClipboard("public_key", publicKey)
                            Toast.makeText(this, "Public key copied", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Copy Private") { _, _ ->
                            copyToClipboard("private_key", privateKey)
                            Toast.makeText(this, "Private key copied", Toast.LENGTH_SHORT).show()
                        }
                        .setNeutralButton("Close", null)

                    builder.show()
                }

                private fun copyToClipboard(label: String, text: String) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(label, text)
                    clipboard.setPrimaryClip(clip)
                }
            }