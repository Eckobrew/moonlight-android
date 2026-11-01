package com.moonlight.material.core.security

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HostTrustException(message: String) : CertificateException(message)

object HostTrust {

    fun certificateDER(pem: String): ByteArray? {
        return try {
            val clean = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim()
            Base64.getDecoder().decode(clean)
        } catch (e: Exception) {
            null
        }
    }

    fun parseCertificate(der: ByteArray): X509Certificate? {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    fun certificateSignature(der: ByteArray): ByteArray? {
        val cert = parseCertificate(der) ?: return null
        return cert.signature
    }

    /**
     * Verifies RSA-SHA256 signature on server secret using the server's public key from certificateDER.
     * Matches HostTrust.swift:verify
     */
    fun verify(secret: ByteArray, signature: ByteArray, certificateDER: ByteArray): Boolean {
        return try {
            val cert = parseCertificate(certificateDER) ?: return false
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(cert.publicKey)
            verifier.update(secret)
            verifier.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates an X509TrustManager pinned strictly to a specific server certificate DER.
     * FAILS CLOSED if pinnedCertificateDER is null or invalid.
     * Matches PinnedHostDelegate in HostTrust.swift:92-105.
     */
    fun createPinnedTrustManager(pinnedCertificateDER: ByteArray?): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (pinnedCertificateDER == null || pinnedCertificateDER.isEmpty()) {
                    throw HostTrustException("HTTPS connection rejected: No pinned certificate provided for host (fail closed).")
                }
                if (chain.isNullOrEmpty()) {
                    throw HostTrustException("HTTPS connection rejected: Empty server certificate chain.")
                }
                val serverCert = chain[0]
                val serverDer = serverCert.encoded
                if (!Arrays.equals(serverDer, pinnedCertificateDER)) {
                    throw HostTrustException("Pinned certificate does not match host certificate!")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    /**
     * Builds SSLSocketFactory with mTLS (client certificate + private key) and server certificate pinning.
     * If pinnedCertificateDER is null, mTLS identity is never sent and trust manager fails closed.
     * Matches HostTrust.swift:106-112.
     */
    fun createPinnedSSLSocketFactory(
        pinnedCertificateDER: ByteArray?,
        certManager: CertificateManager
    ): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManager = createPinnedTrustManager(pinnedCertificateDER)

        val clientCert = certManager.getClientCertificate()
        val clientKey = certManager.getClientPrivateKey()

        // Send client identity ONLY when a pinned certificate exists (HostTrust.swift:107)
        val keyManagers = if (pinnedCertificateDER != null && clientCert != null && clientKey != null) {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("cert", clientCert)
                setKeyEntry("key", clientKey, "".toCharArray(), arrayOf(clientCert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, "".toCharArray())
            }
            kmf.keyManagers
        } else {
            null
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf<TrustManager>(trustManager), null)
        }

        return Pair(sslContext.socketFactory, trustManager)
    }
}
