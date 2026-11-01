package com.moonlight.material.core.security

import android.content.Context
import java.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

class CertificateManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("moonlight_cert_prefs", Context.MODE_PRIVATE)

    var uniqueId: String
        private set

    private var clientKeyPair: KeyPair? = null
    private var clientCert: X509Certificate? = null
    private var clientCertPem: String? = null

    init {
        val storedUniqueId = prefs.getString("unique_id", null)
        uniqueId = if (storedUniqueId != null) {
            storedUniqueId
        } else {
            val generated = CryptoUtils.hexString(CryptoUtils.randomBytes(8)).uppercase()
            prefs.edit().putString("unique_id", generated).apply()
            generated
        }
        loadOrGenerateCertificate()
    }

    private fun loadOrGenerateCertificate() {
        val certPem = prefs.getString("client_cert_pem", null)
        val privKeyB64 = prefs.getString("client_private_key_b64", null)
        val pubKeyB64 = prefs.getString("client_public_key_b64", null)

        if (certPem != null && privKeyB64 != null && pubKeyB64 != null) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val privKeyBytes = Base64.getDecoder().decode(privKeyB64)
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privKeyBytes))

                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(certPem.toByteArray(Charsets.UTF_8))) as X509Certificate

                clientKeyPair = KeyPair(cert.publicKey, privKey)
                clientCert = cert
                clientCertPem = certPem
                return
            } catch (e: Exception) {
                // If loading fails, regenerate
            }
        }

        generateNewCertificate()
    }

    private fun generateNewCertificate() {
        try {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val keyPair = kpg.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 1000L * 60 * 60 * 24) // 1 day ago
            val notAfter = Date(now + 1000L * 60 * 60 * 24 * 365 * 20) // 20 years

            val serial = BigInteger.valueOf(now)
            val owner = X500Name("CN=NVIDIA GameStream Client")

            val certBuilder = JcaX509v3CertificateBuilder(
                owner,
                serial,
                notBefore,
                notAfter,
                owner,
                keyPair.public
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certHolder = certBuilder.build(signer)
            val cert = JcaX509CertificateConverter().getCertificate(certHolder)

            val certDer = cert.encoded
            val certB64 = Base64.getEncoder().encodeToString(certDer)
            val pem = "-----BEGIN CERTIFICATE-----\n$certB64\n-----END CERTIFICATE-----\n"

            val privKeyB64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
            val pubKeyB64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

            prefs.edit()
                .putString("client_cert_pem", pem)
                .putString("client_private_key_b64", privKeyB64)
                .putString("client_public_key_b64", pubKeyB64)
                .apply()

            clientKeyPair = keyPair
            clientCert = cert
            clientCertPem = pem
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate client certificate", e)
        }
    }

    fun getClientCertificatePem(): String {
        return clientCertPem ?: ""
    }

    fun getClientCertificate(): X509Certificate? {
        return clientCert
    }

    fun getClientPrivateKey(): PrivateKey? {
        return clientKeyPair?.private
    }

    fun getCertificateSignature(): ByteArray {
        val cert = clientCert ?: return ByteArray(0)
        return cert.signature
    }

    fun signData(data: ByteArray): ByteArray? {
        return try {
            val privateKey = clientKeyPair?.private ?: return null
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(data)
            signature.sign()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: CertificateManager? = null

        fun initialize(context: Context): CertificateManager {
            return instance ?: synchronized(this) {
                instance ?: CertificateManager(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(): CertificateManager {
            return instance ?: error("CertificateManager must be initialized with Context first")
        }
    }
}
