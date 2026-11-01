package com.moonlight.material.networking

import java.util.Base64
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.core.security.CryptoUtils
import com.moonlight.material.core.security.HostTrust
import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Arrays

sealed class PairingState {
    data object Idle : PairingState()
    data class DisplayingPin(val pin: String) : PairingState()
    data class Progress(val stage: Int, val message: String) : PairingState()
    data class Success(val host: Host) : PairingState()
    data class Failed(val error: String) : PairingState()
}

class PairingService(
    private val httpClient: SunshineHTTPClient,
    private val certificateManager: CertificateManager
) {
    private val TAG = "PAIRING"

    suspend fun pair(
        host: Host,
        onPinGenerated: (String) -> Unit,
        onProgress: (Int, String) -> Unit
    ): Host = withContext(Dispatchers.IO) {
        val uniqueId = certificateManager.uniqueId
        if (host.needsCertificatePairing) {
            httpClient.unpair(host)
        }

        val clientCertPem = certificateManager.getClientCertificatePem()
        val clientCertHex = CryptoUtils.hexString(clientCertPem)

        val pin = String.format("%04d", (1000..9999).random())
        val clientSalt = CryptoUtils.randomBytes(16)
        val clientSaltHex = CryptoUtils.hexString(clientSalt)

        AppLogger.info("Starting pairing with ${host.name} (${host.address})", TAG)
        onPinGenerated(pin)

        val aesKey = CryptoUtils.deriveKey(clientSalt, pin)

        try {
            // Stage 1: phrase=getservercert
            onProgress(1, "Waiting for PIN entry in Sunshine Web UI…")
            AppLogger.info("[Stage 1/5] getservercert url...", TAG)
            val getCertUrl = "http://${host.urlAddress}:${host.effectiveHTTPPort}/pair?uniqueid=$uniqueId&devicename=Moonlight%20Android&updateState=1&phrase=getservercert&salt=$clientSaltHex&clientcert=$clientCertHex"
            val (_, certDict) = httpClient.fetchRaw(getCertUrl, timeoutSec = 180, usePairingSession = true)

            if (certDict["paired"] != "1") {
                val msg = certDict["status_message"] ?: "Stage 1 rejected by host"
                throw IOException("Stage 1 failed: $msg")
            }

            val plainCertHex = certDict["plaincert"]
                ?: throw IOException("Host did not return certificate. Server may already be pairing.")
            val plainCertData = CryptoUtils.dataFromHex(plainCertHex)
                ?: throw IOException("Invalid plaincert hex data")
            val serverCertPem = String(plainCertData, Charsets.UTF_8)

            val serverCertDER = HostTrust.certificateDER(serverCertPem)
                ?: throw IOException("Failed to parse host server certificate DER")
            val serverCertSig = HostTrust.certificateSignature(serverCertDER)
                ?: throw IOException("Failed to parse host certificate signature")

            AppLogger.info("[Stage 1/5] Host accepted PIN! Retrieved server certificate", TAG)

            // Stage 2: clientchallenge
            onProgress(2, "Exchanging cryptographic challenge…")
            AppLogger.info("[Stage 2/5] Sending client challenge...", TAG)
            val randomChallenge = CryptoUtils.randomBytes(16)
            val encryptedChallenge = CryptoUtils.aes128EcbEncrypt(randomChallenge, aesKey)
                ?: throw IOException("Failed to encrypt client challenge")
            val challengeHex = CryptoUtils.hexString(encryptedChallenge)

            val challengeUrl = "http://${host.urlAddress}:${host.effectiveHTTPPort}/pair?uniqueid=$uniqueId&devicename=Moonlight%20Android&updateState=1&clientchallenge=$challengeHex"
            val (_, challengeDict) = httpClient.fetchRaw(challengeUrl, timeoutSec = 10)

            if (challengeDict["paired"] != "1") {
                val msg = challengeDict["status_message"] ?: "Stage 2 rejected"
                throw IOException("Stage 2 failed: $msg")
            }

            val challengeResponseHex = challengeDict["challengeresponse"]
                ?: throw IOException("Stage 2 failed: missing challengeresponse")
            val challengeResponseCipher = CryptoUtils.dataFromHex(challengeResponseHex)
                ?: throw IOException("Stage 2 failed: invalid challengeresponse hex")
            val challengeResponseData = CryptoUtils.aes128EcbDecrypt(challengeResponseCipher, aesKey)
                ?: throw IOException("Stage 2 failed: challenge decryption failed")

            if (challengeResponseData.size < 48) {
                throw IOException("Stage 2 failed: Incorrect PIN or invalid challenge response size")
            }

            val serverResponse = challengeResponseData.copyOfRange(0, 32)
            val serverChallenge = challengeResponseData.copyOfRange(32, 48)
            AppLogger.info("[Stage 2/5] Received server challenge", TAG)

            // Stage 3: serverchallengeresp
            onProgress(3, "Verifying PIN challenge response…")
            AppLogger.info("[Stage 3/5] Computing server challenge response and verifying PIN...", TAG)
            val clientSecretData = CryptoUtils.randomBytes(16)
            val clientCertSig = certificateManager.getCertificateSignature()

            val challengeRespPayload = ByteArray(serverChallenge.size + clientCertSig.size + clientSecretData.size)
            System.arraycopy(serverChallenge, 0, challengeRespPayload, 0, serverChallenge.size)
            System.arraycopy(clientCertSig, 0, challengeRespPayload, serverChallenge.size, clientCertSig.size)
            System.arraycopy(clientSecretData, 0, challengeRespPayload, serverChallenge.size + clientCertSig.size, clientSecretData.size)

            val challengeResponseHash = CryptoUtils.sha256(challengeRespPayload)
            val encryptedChallengeResponseHash = CryptoUtils.aes128EcbEncrypt(challengeResponseHash, aesKey)
                ?: throw IOException("Failed to encrypt challenge response hash")
            val serverChallengeRespHex = CryptoUtils.hexString(encryptedChallengeResponseHash)

            val serverChallengeRespUrl = "http://${host.urlAddress}:${host.effectiveHTTPPort}/pair?uniqueid=$uniqueId&devicename=Moonlight%20Android&updateState=1&serverchallengeresp=$serverChallengeRespHex"
            val (_, serverRespDict) = httpClient.fetchRaw(serverChallengeRespUrl, timeoutSec = 10)

            if (serverRespDict["paired"] != "1") {
                val msg = serverRespDict["status_message"] ?: "Stage 3 rejected"
                throw IOException("Stage 3 failed: $msg")
            }

            val pairingSecretHex = serverRespDict["pairingsecret"]
                ?: throw IOException("Stage 3 failed: host did not return pairing secret")
            val pairingSecretData = CryptoUtils.dataFromHex(pairingSecretHex)
                ?: throw IOException("Stage 3 failed: invalid pairingsecret hex")

            if (pairingSecretData.size <= 16) {
                throw IOException("Stage 3 failed: invalid pairing secret length")
            }

            val serverSecret = pairingSecretData.copyOfRange(0, 16)
            val serverSecretSignature = pairingSecretData.copyOfRange(16, pairingSecretData.size)

            if (!HostTrust.verify(serverSecret, serverSecretSignature, serverCertDER)) {
                throw IOException("Stage 3 failed: Host secret signature verification failed")
            }

            // Verify PIN correctness
            val expectedPayload = ByteArray(randomChallenge.size + serverCertSig.size + serverSecret.size)
            System.arraycopy(randomChallenge, 0, expectedPayload, 0, randomChallenge.size)
            System.arraycopy(serverCertSig, 0, expectedPayload, randomChallenge.size, serverCertSig.size)
            System.arraycopy(serverSecret, 0, expectedPayload, randomChallenge.size + serverCertSig.size, serverSecret.size)
            val expectedHash = CryptoUtils.sha256(expectedPayload)

            if (!Arrays.equals(expectedHash, serverResponse)) {
                throw IOException("Stage 3 failed: Incorrect PIN entered on host!")
            }
            AppLogger.info("[Stage 3/5] Cryptographic PIN verification succeeded!", TAG)

            // Stage 4: clientpairingsecret
            onProgress(4, "Signing client secret…")
            AppLogger.info("[Stage 4/5] Signing client pairing secret...", TAG)
            val clientSignature = certificateManager.signData(clientSecretData)
                ?: throw IOException("Stage 4 failed: Unable to sign client pairing secret")

            val clientPairingSecret = ByteArray(clientSecretData.size + clientSignature.size)
            System.arraycopy(clientSecretData, 0, clientPairingSecret, 0, clientSecretData.size)
            System.arraycopy(clientSignature, 0, clientPairingSecret, clientSecretData.size, clientSignature.size)
            val clientPairingSecretHex = CryptoUtils.hexString(clientPairingSecret)

            val clientSecretUrl = "http://${host.urlAddress}:${host.effectiveHTTPPort}/pair?uniqueid=$uniqueId&devicename=Moonlight%20Android&updateState=1&clientpairingsecret=$clientPairingSecretHex"
            val (_, clientSecretDict) = httpClient.fetchRaw(clientSecretUrl, timeoutSec = 10)

            if (clientSecretDict["paired"] != "1") {
                val msg = clientSecretDict["status_message"] ?: "Stage 4 rejected"
                throw IOException("Stage 4 failed: $msg")
            }
            AppLogger.info("[Stage 4/5] Client pairing secret accepted", TAG)

            // Stage 5: phrase=pairchallenge over pinned HTTPS
            onProgress(5, "Verifying encrypted mTLS connection…")
            AppLogger.info("[Stage 5/5] Establishing mTLS pairchallenge...", TAG)
            val pairChallengeUrl = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/pair?uniqueid=$uniqueId&devicename=Moonlight%20Android&updateState=1&phrase=pairchallenge"
            val (_, pairChallengeDict) = httpClient.fetchRaw(pairChallengeUrl, timeoutSec = 15, certificateDER = serverCertDER)

            if (pairChallengeDict["paired"] != "1") {
                val msg = pairChallengeDict["status_message"] ?: "Stage 5 rejected over HTTPS"
                throw IOException("Stage 5 failed: $msg")
            }

            val serverCertBase64 = Base64.getEncoder().encodeToString(serverCertDER)
            val candidate = host.copy(
                isPaired = true,
                serverCertificateDER = serverCertBase64
            )

            val (verifiedHost, _) = httpClient.fetchServerInfo(candidate)
            if (!verifiedHost.isSecurelyPaired) {
                throw IOException("Host did not confirm pairing over verified HTTPS")
            }

            AppLogger.info("Pairing completed securely with host ${verifiedHost.name}", TAG)
            verifiedHost
        } catch (e: Exception) {
            httpClient.unpair(host)
            throw e
        }
    }
}
