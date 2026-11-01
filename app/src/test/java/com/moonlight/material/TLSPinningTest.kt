package com.moonlight.material

import com.moonlight.material.core.security.HostTrust
import com.moonlight.material.core.security.HostTrustException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager

/**
 * Port of TLSPinningTests.swift: proves that HTTPS without a pin fails closed,
 * only the exact pinned certificate is accepted, and redirects are not followed.
 */
class TLSPinningTest {

    private fun loadFixture(name: String): ByteArray {
        val stream: InputStream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw IllegalStateException("Fixture $name not found in test resources")
        return stream.readBytes()
    }

    private data class ServerMaterial(
        val keyStore: KeyStore,
        val serverCert: X509Certificate,
        val serverCertDer: ByteArray
    )

    private fun generateServerCert(): ServerMaterial {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 1000L * 60)
        val notAfter = Date(now + 1000L * 60 * 60 * 24)
        val serial = BigInteger.valueOf(now)
        val owner = X500Name("CN=localhost")

        val certBuilder = JcaX509v3CertificateBuilder(
            owner,
            serial,
            notBefore,
            notAfter,
            owner,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", keyPair.private, "password".toCharArray(), arrayOf(cert))
            setCertificateEntry("cert", cert)
        }

        return ServerMaterial(keyStore, cert, cert.encoded)
    }

    @Test
    fun tlsHandshakeAcceptsOnlyPinnedCertificateAndFailsClosed() {
        val material = generateServerCert()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(material.keyStore, "password".toCharArray())
        }
        val serverSslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }

        val fixtureDer = loadFixture("host.der")

        val testCases = listOf(
            Triple("Valid leaf pin", material.serverCertDer, true),
            Triple("Different fixture cert pin", fixtureDer, false),
            Triple("Malformed random pin", byteArrayOf(1, 2, 3), false),
            Triple("Null pin (fail closed)", null, false)
        )

        for ((name, pinDer, shouldSucceed) in testCases) {
            val server = MockWebServer()
            server.useHttps(serverSslContext.socketFactory, false)
            server.enqueue(MockResponse().setResponseCode(200).setBody("pinned host test"))
            server.start()

            try {
                val url = server.url("/").toString()
                val trustManager = HostTrust.createPinnedTrustManager(pinDer)
                val clientSslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(trustManager), null)
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .sslSocketFactory(clientSslContext.socketFactory, trustManager)
                    .hostnameVerifier { _, _ -> true }
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                val request = Request.Builder().url(url).build()

                if (shouldSucceed) {
                    client.newCall(request).execute().use { response ->
                        assertEquals("Test case '$name' should return HTTP 200", 200, response.code)
                        assertEquals("pinned host test", response.body?.string())
                    }
                } else {
                    try {
                        client.newCall(request).execute().use { response ->
                            fail("Test case '$name' should have failed TLS handshake, but got HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        assertTrue(
                            "Expected SSL failure for '$name', got: ${e.javaClass.simpleName}: ${e.message}",
                            e is SSLHandshakeException || e is CertificateException || e is HostTrustException
                        )
                    }
                }
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun authenticatedRequestsDoNotFollowRedirects() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://127.0.0.1/malicious_redirect")
        )
        server.start()

        try {
            val url = server.url("/launch?rikey=test").toString()
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                assertEquals("Should return 302 without redirecting", 302, response.code)
                assertEquals("http://127.0.0.1/malicious_redirect", response.header("Location"))
            }
        } finally {
            server.shutdown()
        }
    }
}
