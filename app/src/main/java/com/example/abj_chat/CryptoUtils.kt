package com.example.abj_chat

import android.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val HKDF_INFO = "BLE_MSG_V1"
    private val rnd = SecureRandom()
    private val b64 = Base64.URL_SAFE or Base64.NO_WRAP

    private fun deriveShared(privB64: String, pubB64: String): ByteArray {
        val priv = X25519PrivateKeyParameters(Base64.decode(privB64, b64), 0)
        val pub = X25519PublicKeyParameters(Base64.decode(pubB64, b64), 0)
        val secret = ByteArray(32)
        priv.generateSecret(pub, secret, 0)
        return hkdf(secret, 32)
    }

    private fun hkdf(ikm: ByteArray, len: Int): ByteArray {
        val prk = hmac(ByteArray(32) { 0 }, ikm)
        var t = ByteArray(0)
        val out = ArrayList<Byte>()
        var c = 1
        while (out.size < len) {
            val input = t + HKDF_INFO.toByteArray() + c.toByte()
            t = hmac(prk, input)
            out.addAll(t.toList())
            c++
        }
        return out.take(len).toByteArray()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun encrypt(privSender: String, pubRecipient: String, plain: String): String {
        val key = deriveShared(privSender, pubRecipient)
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance(AES_MODE)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, b64)
    }

    fun decrypt(privReceiver: String, pubSender: String, enc: String): String {
        val combo = Base64.decode(enc, b64)
        require(combo.size > 12) { "cipher too short" }
        val iv = combo.sliceArray(0 until 12)
        val ct = combo.sliceArray(12 until combo.size)
        val key = deriveShared(privReceiver, pubSender)
        val c = Cipher.getInstance(AES_MODE)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}