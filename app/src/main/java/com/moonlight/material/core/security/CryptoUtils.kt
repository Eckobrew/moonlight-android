package com.moonlight.material.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private val secureRandom = SecureRandom()

    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun hexString(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexString(text: String): String {
        return hexString(text.toByteArray(Charsets.UTF_8))
    }

    fun dataFromHex(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return null
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            if (high == -1 || low == -1) return null
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * Derive AES key matching Moonlight-Qt / Moonlight-Mac:
     * SHA256(salt + pin.utf8)[0 until 16]
     */
    fun deriveKey(salt: ByteArray, pin: String): ByteArray {
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        val combined = ByteArray(salt.size + pinBytes.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(pinBytes, 0, combined, salt.size, pinBytes.size)
        val hash = sha256(combined)
        return hash.copyOfRange(0, 16)
    }

    fun aes128EcbEncrypt(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            // If data is not multiple of 16, pad with zeros
            val paddedLen = ((data.size + 15) / 16) * 16
            val input = if (data.size == paddedLen) data else data.copyOf(paddedLen)
            cipher.doFinal(input)
        } catch (e: Exception) {
            null
        }
    }

    fun aes128EcbDecrypt(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(data)
        } catch (e: Exception) {
            null
        }
    }

    fun aes128CbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            null
        }
    }

    fun aes128CbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            null
        }
    }
}
