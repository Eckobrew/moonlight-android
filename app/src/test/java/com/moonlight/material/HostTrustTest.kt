package com.moonlight.material

import com.moonlight.material.core.models.Host
import com.moonlight.material.core.security.HostTrust
import com.moonlight.material.core.security.HostTrustException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class HostTrustTest {

    private fun loadFixture(name: String): ByteArray {
        val stream: InputStream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw IllegalStateException("Fixture $name not found in test resources")
        return stream.readBytes()
    }

    @Test
    fun pairingVerifiesSignatureAndRejectsTampering() {
        val certificate = loadFixture("host.der")
        val secret = loadFixture("secret.bin")
        val signature = loadFixture("signature.bin")

        // Exact signature verification
        assertTrue(HostTrust.verify(secret, signature, certificate))

        // Tampered secret
        assertFalse(HostTrust.verify("tampered".toByteArray(Charsets.UTF_8), signature, certificate))

        // Damaged signature
        val damaged = signature.clone()
        damaged[0] = (damaged[0].toInt() xor 1).toByte()
        assertFalse(HostTrust.verify(secret, damaged, certificate))

        // Empty certificate
        assertFalse(HostTrust.verify(secret, signature, ByteArray(0)))
    }

    @Test
    fun certificatePinRequiresExactLeafCertificateAndFailsClosedWhenUnpinned() {
        val certificate = loadFixture("host.der")
        val parsedCert = HostTrust.parseCertificate(certificate)
        assertNotNull(parsedCert)
        val chain = arrayOf<X509Certificate>(parsedCert!!)

        // 1. Exact match succeeds
        val validTrustManager = HostTrust.createPinnedTrustManager(certificate)
        validTrustManager.checkServerTrusted(chain, "RSA")

        // 2. Mismatched pin fails
        val different = certificate.clone()
        different[different.size - 1] = (different[different.size - 1].toInt() xor 1).toByte()
        val invalidTrustManager = HostTrust.createPinnedTrustManager(different)
        try {
            invalidTrustManager.checkServerTrusted(chain, "RSA")
            fail("Expected CertificateException for mismatched certificate pin")
        } catch (e: CertificateException) {
            // Success
        }

        // 3. Null pin FAILS CLOSED (Reference: HostTrust.swift:101-103)
        val nullTrustManager = HostTrust.createPinnedTrustManager(null)
        try {
            nullTrustManager.checkServerTrusted(chain, "RSA")
            fail("Expected HostTrustException when pinned certificate is null (fail closed)")
        } catch (e: HostTrustException) {
            // Success
        }

        // 4. Empty pin FAILS CLOSED
        val emptyTrustManager = HostTrust.createPinnedTrustManager(ByteArray(0))
        try {
            emptyTrustManager.checkServerTrusted(chain, "RSA")
            fail("Expected HostTrustException when pinned certificate is empty")
        } catch (e: HostTrustException) {
            // Success
        }

        // 5. Empty chain fails
        try {
            validTrustManager.checkServerTrusted(emptyArray(), "RSA")
            fail("Expected CertificateException for empty server chain")
        } catch (e: CertificateException) {
            // Success
        }
    }

    @Test
    fun certificateParsingHandlesMalformedDER() {
        val pem = String(loadFixture("host.pem"), Charsets.UTF_8)
        val der = loadFixture("host.der")

        val parsedDer = HostTrust.certificateDER(pem)
        assertNotNull(parsedDer)
        assertTrue(der.contentEquals(parsedDer!!))

        assertEquals(256, HostTrust.certificateSignature(der)?.size)
        assertNull(HostTrust.certificateDER("not a certificate"))

        for (length in 0 until der.size) {
            assertNull(HostTrust.certificateSignature(der.copyOfRange(0, length)))
        }
        assertNull(HostTrust.certificateSignature(byteArrayOf(0x30, 0xFF.toByte())))
    }

    @Test
    fun legacyHostsRequirePairingWithoutLosingSavedIdentity() {
        val der = loadFixture("host.der")
        val b64 = java.util.Base64.getEncoder().encodeToString(der)

        val unpinnedHost = Host(
            id = "test-1",
            name = "Test Host",
            address = "192.168.1.100",
            serverId = "server-123",
            isPaired = true,
            serverCertificateDER = null
        )

        assertTrue(unpinnedHost.needsCertificatePairing)
        assertFalse(unpinnedHost.isSecurelyPaired)

        val pinnedHost = unpinnedHost.copy(serverCertificateDER = b64)
        assertFalse(pinnedHost.needsCertificatePairing)
        assertTrue(pinnedHost.isSecurelyPaired)
    }
}
