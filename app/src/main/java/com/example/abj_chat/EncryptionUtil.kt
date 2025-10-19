// kotlin
package com.example.abj_chat

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionUtil {
    private val b64Flags = Base64.URL_SAFE or Base64.NO_WRAP
    private val secureRandom = SecureRandom()

    private fun deriveSharedSecret(privateKeyB64: String, peerPublicB64: String): ByteArray {
        val privBytes = Base64.decode(privateKeyB64, b64Flags)
        val pubBytes = Base64.decode(peerPublicB64, b64Flags)
        val priv = X25519PrivateKeyParameters(privBytes, 0)
        val pub = X25519PublicKeyParameters(pubBytes, 0)
        val agreement = X25519Agreement()
        agreement.init(priv)
        val secret = ByteArray(32)
        agreement.calculateAgreement(pub, secret, 0)
        return secret
    }

    private fun hkdfSha256(input: ByteArray, length: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(input, null, null))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    fun encryptFor(recipientPublicB64: String, senderPrivateB64: String, plaintext: String): String {
        val shared = deriveSharedSecret(senderPrivateB64, recipientPublicB64)
        val key = hkdfSha256(shared, 32)
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val sk = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, sk, spec)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ct
        return Base64.encodeToString(combined, b64Flags)
    }

    fun decryptFrom(senderPublicB64: String, recipientPrivateB64: String, payloadB64: String): String {
        val combined = Base64.decode(payloadB64, b64Flags)
        if (combined.size < 13) throw IllegalArgumentException("Invalid payload")
        val iv = combined.copyOfRange(0, 12)
        val ct = combined.copyOfRange(12, combined.size)
        val shared = deriveSharedSecret(recipientPrivateB64, senderPublicB64)
        val key = hkdfSha256(shared, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val sk = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, sk, spec)
        val pt = cipher.doFinal(ct)
        return String(pt, Charsets.UTF_8)
    }
}