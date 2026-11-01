package com.moonlight.material

import com.moonlight.material.core.models.Host
import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.core.security.CryptoUtils
import com.moonlight.material.core.security.HostTrust
import com.moonlight.material.networking.PairingService
import com.moonlight.material.networking.SunshineHTTPClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class PairingServiceTest {

    @Test
    fun testPinDerivationAndChallengeCrypto() {
        // Test salt + PIN key derivation
        val salt = CryptoUtils.randomBytes(16)
        val pin = "1234"
        val derivedKey = CryptoUtils.deriveKey(salt, pin)
        assertEquals(16, derivedKey.size)

        // Test AES-128-ECB challenge encryption/decryption round-trip
        val challenge = CryptoUtils.randomBytes(16)
        val encrypted = CryptoUtils.aes128EcbEncrypt(challenge, derivedKey)
        assertNotNull(encrypted)

        val decrypted = CryptoUtils.aes128EcbDecrypt(encrypted!!, derivedKey)
        assertNotNull(decrypted)
        assertArrayEquals(challenge, decrypted)
    }

    @Test
    fun testSignatureVerificationWithFixture() {
        val hostDerBytes = javaClass.classLoader?.getResourceAsStream("host.der")?.readBytes()
        assertNotNull(hostDerBytes)

        val secretBytes = javaClass.classLoader?.getResourceAsStream("secret.bin")?.readBytes()
        assertNotNull(secretBytes)

        val signatureBytes = javaClass.classLoader?.getResourceAsStream("signature.bin")?.readBytes()
        assertNotNull(signatureBytes)

        val isValid = HostTrust.verify(secretBytes!!, signatureBytes!!, hostDerBytes!!)
        assertTrue("Fixture signature must verify against host.der", isValid)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
