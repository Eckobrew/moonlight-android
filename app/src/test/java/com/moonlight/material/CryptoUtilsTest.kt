package com.moonlight.material

import com.moonlight.material.core.security.CryptoUtils
import org.junit.Assert.*
import org.junit.Test

class CryptoUtilsTest {

    @Test
    fun testHexConversion() {
        val original = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val hex = CryptoUtils.hexString(original)
        assertEquals("0123456789abcdef", hex.lowercase())

        val back = CryptoUtils.dataFromHex(hex)
        assertNotNull(back)
        assertArrayEquals(original, back)
    }

    @Test
    fun testKeyDerivation() {
        val salt = CryptoUtils.dataFromHex("0123456789ABCDEF0123456789ABCDEF")!!
        val pin = "1234"
        val key = CryptoUtils.deriveKey(salt, pin)
        assertEquals(16, key.size)
    }

    @Test
    fun testAes128EcbRoundTrip() {
        val key = CryptoUtils.randomBytes(16)
        val data = "HelloWorld123456".toByteArray(Charsets.UTF_8) // 16 bytes

        val encrypted = CryptoUtils.aes128EcbEncrypt(data, key)
        assertNotNull(encrypted)
        assertEquals(16, encrypted!!.size)

        val decrypted = CryptoUtils.aes128EcbDecrypt(encrypted, key)
        assertNotNull(decrypted)
        assertArrayEquals(data, decrypted)
    }
}
