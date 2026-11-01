package com.moonlight.material.networking

import java.util.Base64
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostCapabilities
import com.moonlight.material.core.models.HostHardwareMetrics
import com.moonlight.material.core.models.HostStatus
import com.moonlight.material.core.models.HostXMLValues
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.core.security.CryptoUtils
import com.moonlight.material.core.security.HostTrust
import com.moonlight.material.core.security.HostTrustException
import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class SunshineHTTPClient(
    private val certificateManager: CertificateManager? = null,
    val uniqueID: () -> String = { certificateManager?.uniqueId ?: "fixture" },
    var extensionTransport: (suspend (Request, Host) -> Pair<ByteArray, Int>)? = null
) {
    // Unpinned plain HTTP client - strictly for unpinned HTTP calls (pairing stages 1-4, http serverinfo, unpair)
    // Refuses all HTTP and SSL redirects (HostTrust.swift:117-123)
    private val baseHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // Unpinned pairing HTTP client - allows long timeout for user PIN entry on host
    private val pairingHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // Stage 1 waits for user PIN entry
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val closeConnection = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("Connection", "close").build())
    }


    /**
     * Obtains an authenticated OkHttpClient for the given host.
     * Enforces certificate pinning: HTTPS without a valid pinned certificate FAILS CLOSED (HostTrust.swift:93-115).
     */
    fun getClientForHost(host: Host): OkHttpClient {
        val certDerBase64 = host.serverCertificateDER
        if (certDerBase64.isNullOrEmpty()) {
            throw HostTrustException("HTTPS connection rejected: Host ${host.name} has no pinned certificate (fail closed).")
        }
        val certMgr = certificateManager ?: throw HostTrustException("CertificateManager required for HTTPS connections.")

        // Android TLS session resumption can fail against GameStream's mTLS listener.
        // Match Moonlight Android: use a fresh SSLContext for each request.
        return run {
            val der = Base64.getDecoder().decode(certDerBase64)
            val (sslSocketFactory, trustManager) = HostTrust.createPinnedSSLSocketFactory(der, certMgr)
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(closeConnection)
                .sslSocketFactory(sslSocketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // Validated via certificateDER byte-exact pin
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }

    /**
     * Executes raw request for pairing handshake or discovery.
     * If certificateDER is provided, uses strict pinned HTTPS.
     * If certificateDER is null, uses plain unpinned HTTP (used only for stages 1-4).
     */
    suspend fun fetchRaw(
        urlString: String,
        timeoutSec: Long = 10,
        certificateDER: ByteArray? = null,
        usePairingSession: Boolean = false
    ): Pair<Int, Map<String, String>> = withContext(Dispatchers.IO) {
        val client = if (certificateDER != null) {
            val certMgr = certificateManager ?: throw HostTrustException("CertificateManager required for HTTPS connections.")
            val (sslSocketFactory, trustManager) = HostTrust.createPinnedSSLSocketFactory(certificateDER, certMgr)
            OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .addInterceptor(closeConnection)
                .sslSocketFactory(sslSocketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        } else if (usePairingSession) {
            pairingHttpClient
        } else {
            baseHttpClient
        }

        val request = Request.Builder().url(urlString).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val dict = parseSimpleXml(body)
            Pair(response.code, dict)
        }
    }

    private val remoteProbeTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun fetchServerInfo(host: Host): Pair<Host, Map<String, String>> {
        val (resolved, metadata) = HostRouteResolver.resolve(host) { fetchServerInfoAtAddress(it) }
        val reported = metadata["ExternalIP"]?.trim()?.takeIf { it.isNotEmpty() && it != resolved.address && it != "0.0.0.0" }
        val port = metadata["ExternalPort"]?.toIntOrNull()?.takeIf { it in 6..65535 }
        if (resolved.isSecurelyPaired && !resolved.serverId.isNullOrBlank() && reported != null && port != null &&
            resolved.effectiveAddress == resolved.address && resolved.remoteAddress != reported) {
            val now = System.currentTimeMillis()
            val key = "${resolved.id}:$reported:$port"
            if (now - (remoteProbeTimes[key] ?: 0L) > 60_000) {
                remoteProbeTimes[key] = now
                try {
                    val verified = verifyRemoteAddress(resolved, reported, port, port - 5)
                    return verified.copy(activeAddress = resolved.activeAddress) to metadata
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.debug("Reported remote endpoint could not be verified: ${e.message}", "HTTP")
                }
            }
        }
        return resolved to metadata
    }

    suspend fun verifyRemoteAddress(host: Host, address: String, httpPort: Int, httpsPort: Int): Host {
        if (!host.isSecurelyPaired || host.serverId.isNullOrBlank()) {
            throw HostTrustException("Pair with the host locally before saving a remote address.")
        }
        require(httpPort in 1..65535 && httpsPort in 1..65535) { "Ports must be between 1 and 65535" }
        val candidate = host.copy(remoteAddress = address.trim(), remoteHTTPPort = httpPort,
            remoteHTTPSPort = httpsPort, activeAddress = address.trim())
        fetchServerInfoAtAddress(candidate) // Verifies pinned certificate AND server identity.
        return candidate.copy(activeAddress = host.activeAddress)
    }

    private suspend fun fetchServerInfoAtAddress(host: Host): Pair<Host, Map<String, String>> = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        var responseData: String? = null
        var isHttps = false

        // Try pinned HTTPS first if host is securely paired with a known cert
        if (host.isSecurelyPaired && host.httpsPort > 0) {
            try {
                val httpsUrl = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/serverinfo?uniqueid=$uniqueId"
                val client = getClientForHost(host).newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
                val request = Request.Builder().url(httpsUrl).build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null && parseSimpleXml(body)["status_code"].let { it == null || it == "200" }) {
                            responseData = body
                            isHttps = true
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.debug("HTTPS serverinfo failed for ${host.name}: ${e.message}", "HTTP")
            }
        }

        // Fallback to plain HTTP serverinfo (unpinned allowed only for initial discovery/pairing status)
        if (responseData == null && host.isSecurelyPaired) {
            throw IOException("Authenticated connection to ${host.name} failed. Check the host is online and this device is still paired.")
        }
        if (responseData == null) {
            val httpUrl = "http://${host.urlAddress}:${host.effectiveHTTPPort}/serverinfo?uniqueid=$uniqueId"
            val request = Request.Builder().url(httpUrl).build()
            baseHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP serverinfo failed with status ${resp.code}")
                }
                responseData = resp.body?.string() ?: throw IOException("Empty serverinfo response")
            }
        }

        val dict = parseSimpleXml(responseData!!)
        checkProtocolResponse(dict)
        if (dict["hostname"] == null && dict["appversion"] == null && dict["uniqueid"] == null) {
            throw IOException("Invalid serverinfo response")
        }
        if (host.serverId != null && !host.serverId.equals(dict["uniqueid"], ignoreCase = true)) {
            throw HostTrustException("The server identity changed. Check the host address.")
        }
        val pairStatus = dict["PairStatus"]
        val isConfirmedPaired = host.isSecurelyPaired || (isHttps && pairStatus == "1")

        val currentAppId = dict["currentgame"]?.toIntOrNull()?.takeIf { it != 0 }
        val status = when {
            isConfirmedPaired && currentAppId != null -> HostStatus.STREAMING
            isConfirmedPaired -> HostStatus.ONLINE
            pairStatus == "1" -> HostStatus.ONLINE
            else -> HostStatus.PAIRING_REQUIRED
        }

        val updatedCapabilities = HostCapabilities.parse(dict, HostXMLValues.elements("ServerCommand", responseData!!))

        val updatedHost = host.copy(
            name = dict["hostname"] ?: host.name,
            status = status,
            isPaired = isConfirmedPaired || pairStatus == "1",
            appVersion = dict["appversion"] ?: host.appVersion,
            gfeVersion = dict["GfeVersion"] ?: host.gfeVersion,
            serverCodecModeSupport = dict["ServerCodecModeSupport"]?.toIntOrNull() ?: host.serverCodecModeSupport,
            macAddress = dict["mac"] ?: host.macAddress,
            httpsPort = if (host.effectiveAddress == host.address) dict["HttpsPort"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: host.httpsPort else host.httpsPort,
            httpPort = host.httpPort,
            serverId = dict["uniqueid"] ?: host.serverId,
            currentAppId = currentAppId,
            currentAppUUID = dict["currentgameuuid"]?.takeIf { it.isNotBlank() },
            lastSeenEpochMs = System.currentTimeMillis(),
            capabilities = updatedCapabilities
        )

        Pair(updatedHost, dict)
    }

    suspend fun fetchAppList(host: Host): List<StreamApp> = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        val url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/applist?uniqueid=$uniqueId"
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Applist failed with code ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty applist response")
            parseAppListXml(body, host)
        }
    }

    suspend fun fetchBoxArt(host: Host, appId: Int, appUUID: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        var url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/appasset?uniqueid=$uniqueId&appid=$appId&AssetType=2&AssetIdx=0"
        if (!appUUID.isNullOrEmpty() && isValidUuid(appUUID)) {
            url += "&appuuid=$appUUID"
        }

        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Boxart request failed with code ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Empty boxart body")
        }
    }

    suspend fun launchApp(
        host: Host,
        appId: Int,
        appUUID: String? = null,
        settings: StreamSettings,
        rikey: String,
        rikeyId: Int,
        commonCExtraParams: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        val mode = "${settings.width}x${settings.height}x${settings.fps}"
        val surroundAudioInfo = settings.audioMode.surroundAudioInfo
        val localAudioPlayMode = if (settings.muteHostAudio) 0 else 1
        val sops = if (settings.optimizeGameSettings) "1" else "0"
        val extraParams = if (commonCExtraParams.isNotEmpty()) {
            commonCExtraParams
        } else {
            try {
                com.moonlight.material.bridge.MoonBridge.getLaunchUrlQueryParameters()
            } catch (t: Throwable) {
                ""
            }
        }

        var url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/launch?uniqueid=$uniqueId&appid=$appId&mode=$mode&additionalStates=1&sops=$sops&rikey=$rikey&rikeyid=${Integer.toUnsignedString(rikeyId)}&localAudioPlayMode=$localAudioPlayMode&surroundAudioInfo=$surroundAudioInfo"
        if (!appUUID.isNullOrEmpty() && isValidUuid(appUUID)) {
            url += "&appuuid=$appUUID"
        }
        url += buildLaunchQuery(settings, host)
        if (extraParams.isNotEmpty()) {
            url += extraParams
        }

        AppLogger.info("Launching $appId on ${host.name} ($mode)", "HTTP")
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Launch failed with HTTP ${response.code}")
            }
            val dict = parseSimpleXml(response.body?.string() ?: "")
            val statusCode = dict["status_code"] ?: "200"
            if (statusCode != "200") {
                val msg = dict["status_message"] ?: "Launch failed"
                throw IOException(msg)
            }
            dict["sessionUrl0"]
        }
    }

    suspend fun resumeApp(
        host: Host,
        settings: StreamSettings,
        rikey: String,
        rikeyId: Int,
        commonCExtraParams: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        val mode = "${settings.width}x${settings.height}x${settings.fps}"
        val surroundAudioInfo = settings.audioMode.surroundAudioInfo
        val localAudioPlayMode = if (settings.muteHostAudio) 0 else 1
        val extraParams = if (commonCExtraParams.isNotEmpty()) {
            commonCExtraParams
        } else {
            try {
                com.moonlight.material.bridge.MoonBridge.getLaunchUrlQueryParameters()
            } catch (t: Throwable) {
                ""
            }
        }

        var url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/resume?uniqueid=$uniqueId&rikey=$rikey&rikeyid=${Integer.toUnsignedString(rikeyId)}&localAudioPlayMode=$localAudioPlayMode&surroundAudioInfo=$surroundAudioInfo"
        url += buildLaunchQuery(settings, host)
        if (extraParams.isNotEmpty()) {
            url += extraParams
        }

        AppLogger.info("Resuming session on ${host.name} ($mode)", "HTTP")
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Resume failed with HTTP ${response.code}")
            }
            val dict = parseSimpleXml(response.body?.string() ?: "")
            val statusCode = dict["status_code"] ?: "200"
            if (statusCode != "200") {
                val msg = dict["status_message"] ?: "Resume failed"
                throw IOException(msg)
            }
            dict["sessionUrl0"]
        }
    }

    suspend fun quitApp(host: Host): Boolean = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        val url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/cancel?uniqueid=$uniqueId"
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val values = parseSimpleXml(response.body?.string().orEmpty())
                checkProtocolResponse(values)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun unpair(host: Host): Boolean = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        val url = "http://${host.urlAddress}:${host.effectiveHTTPPort}/unpair?uniqueid=$uniqueId"
        try {
            baseHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val values = parseSimpleXml(response.body?.string().orEmpty())
                checkProtocolResponse(values)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getClipboardContent(host: Host): String = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        val url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/actions/clipboard?type=text&uniqueid=$uniqueId"
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HostControlError(response.code, "Server returned HTTP ${response.code}")
            }
            response.body?.string() ?: ""
        }
    }

    suspend fun sendClipboardContent(host: Host, text: String) = withContext(Dispatchers.IO) {
        val uniqueId = uniqueID()
        val uuid = UUID.randomUUID().toString().replace("-", "").lowercase()
        val url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/actions/clipboard?uniqueid=$uniqueId&uuid=$uuid&type=text"
        val client = getClientForHost(host)
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val body = text.toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HostControlError(response.code, "Server returned HTTP ${response.code}")
            }
        }
    }

    /**
     * Executes extension request with certificate verification.
     * Matches HostControlClient.swift & SunshineHTTPClient.swift:477-491
     */
    suspend fun extensionRequest(
        host: Host,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        method: String = "GET"
    ): Pair<ByteArray, Int> = withContext(Dispatchers.IO) {
        if (!host.isSecurelyPaired) {
            throw HostTrustException("Host is not securely paired.")
        }
        val builder = StringBuilder("https://${host.urlAddress}:${host.effectiveHTTPSPort}$path")
        val params = mutableListOf<Pair<String, String>>()
        if (!path.startsWith("/power-actions/")) {
            params.add("uniqueid" to uniqueID())
        }
        params.addAll(query)
        if (params.isNotEmpty()) {
            builder.append("?")
            builder.append(params.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            })
        }
        val url = builder.toString()
        val requestBuilder = Request.Builder().url(url)
        if (method == "POST") {
            requestBuilder.post(RequestBody.create(null, ByteArray(0)))
        } else {
            requestBuilder.get()
        }
        val request = requestBuilder.build()

        extensionTransport?.let { transport ->
            return@withContext transport(request, host)
        }

        val client = getClientForHost(host)
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            Pair(bytes, response.code)
        }
    }

    /**
     * Verifies that HTTP status code and XML status_code (if present) are in 200..299.
     * Matches HostControlClient.swift:10-17
     */
    fun checkExtension(data: ByteArray, httpStatusCode: Int): Map<String, String> {
        val xml = parseSimpleXml(String(data, Charsets.UTF_8))
        val status = xml["status_code"]?.toIntOrNull() ?: httpStatusCode
        if (httpStatusCode !in 200..299 || status !in 200..299) {
            throw HostControlError(status, xml["status_message"] ?: "Host request failed ($status).")
        }
        return xml
    }

    /**
     * Checks if host supports runtime bitrate modification.
     * Matches HostControlClient.swift:19-24
     */
    suspend fun supportsRuntimeBitrate(host: Host): Boolean {
        return try {
            val (data, code) = extensionRequest(host, "/api/abr/capabilities")
            if (code != 200) return false
            val json = Json.parseToJsonElement(String(data, Charsets.UTF_8))
            val features = json.jsonObject["features"]?.jsonArray
            features?.any { it.jsonPrimitive.content == "runtime_bitrate" } == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Adjusts active stream bitrate at runtime.
     * Matches HostControlClient.swift:26-32
     */
    suspend fun setRuntimeBitrate(host: Host, kbps: Int): Int {
        if (kbps !in 1..500_000) {
            throw HostControlError(400, "Bitrate must be between 1 and 500000 Kbps.")
        }
        val (data, code) = extensionRequest(host, "/bitrate", query = listOf("bitrate" to kbps.toString()))
        val xml = checkExtension(data, code)
        val applied = xml["bitrate"]?.toIntOrNull()
        if (applied == null || applied !in 1..500_000) {
            throw IOException("Bad server response")
        }
        return applied
    }

    /**
     * Fetches real-time host hardware telemetry (CPU/GPU utilization).
     * Matches HostControlClient.swift:34-42
     */
    suspend fun fetchHardwareMetrics(host: Host): HostHardwareMetrics {
        val (data, code) = extensionRequest(host, "/api/hardware-metrics")
        if (code != 200) {
            throw HostControlError(code, "Host statistics unavailable ($code).")
        }
        val jsonStr = String(data, Charsets.UTF_8)
        val json = Json { ignoreUnknownKeys = true }
        val metrics = json.decodeFromString<HostHardwareMetrics>(jsonStr)
        if (metrics.version != 1 || metrics.interval_ms <= 0) {
            throw IOException("Bad server response")
        }
        return metrics
    }

    /**
     * Initiates host sleep via two-stage handshake with single-use SleepToken.
     * Matches HostControlClient.swift:45-64
     */
    suspend fun sleepHost(host: Host) {
        val (data, code) = extensionRequest(host, "/power-actions/v1")
        val xml = checkExtension(data, code)
        val token = xml["SleepToken"]
        if (xml["PowerActionVersion"] != "1" || xml["SleepSupported"] != "1" || xml["SleepAllowed"] != "1" || xml["SleepAvailable"] != "1" || token.isNullOrEmpty()) {
            throw HostControlError(409, "The PC cannot sleep now. End all host sessions and check this device's sleep permission, then confirm again.")
        }

        currentCoroutineContext().ensureActive()
        val (accepted, result) = try {
            extensionRequest(host, "/power-actions/v1/sleep", query = listOf("token" to token), method = "POST")
        } catch (e: HostControlError) {
            throw e
        } catch (e: Exception) {
            throw HostControlError(-1, "The sleep request's outcome is unknown because its acknowledgement was not received. Confirm again before retrying.")
        }
        val confirmation = checkExtension(accepted, result)
        if (result != 202 || confirmation["status_code"] != "202" || confirmation["SleepAccepted"] != "1") {
            throw HostControlError(result, "Sleep outcome is unknown. Confirm again before retrying.")
        }
    }

    /**
     * Performs host control action (disconnect monitor, disconnect input, terminate stream).
     * Deliberately completes with XML 410 rather than an RTSP URL.
     * Matches HostControlClient.swift:67-90
     */
    suspend fun performHostAction(host: Host, app: StreamApp): String {
        if (!app.isHostAction) {
            throw IOException("Unsupported URL for host action")
        }
        val query = mutableListOf(
            "appid" to app.id.toString(),
            "rikey" to CryptoUtils.hexString(CryptoUtils.randomBytes(16)),
            "rikeyid" to kotlin.random.Random.nextLong(1L, 0xFFFFFFFFL).toString(),
            "localAudioPlayMode" to "0"
        )
        if (!app.uuid.isNullOrEmpty()) {
            query.add("appuuid" to app.uuid)
        }
        val (data, code) = extensionRequest(host, "/launch", query = query)
        val xml = parseSimpleXml(String(data, Charsets.UTF_8))
        val expected = when (app.control) {
            com.moonlight.material.core.models.AppControl.DISCONNECT_MONITOR -> "Remote Monitor disconnected successfully."
            com.moonlight.material.core.models.AppControl.DISCONNECT_INPUT -> "Remote Input disconnected successfully."
            com.moonlight.material.core.models.AppControl.TERMINATE -> "Active stream terminated. Remote Monitor and Remote Input remain connected."
            else -> throw IOException("Unsupported URL for host action")
        }
        if (code != 200 || xml["status_code"] != "410" || xml["status_message"] != expected) {
            val errCode = xml["status_code"]?.toIntOrNull() ?: code
            throw HostControlError(errCode, xml["status_message"] ?: "Host action was not confirmed.")
        }
        return expected
    }

    companion object {
        fun checkProtocolResponse(values: Map<String, String>) {
            val status = values["status_code"] ?: return
            if (status.toIntOrNull() !in 200..299) {
                throw IOException(values["status_message"] ?: "Host request failed ($status)")
            }
        }

        /**
         * Builds launch query parameters.
         * Matches HostLaunchOptions.swift:4-18
         */
        fun buildLaunchQuery(settings: StreamSettings, host: Host? = null): String {
            val mask = java.lang.Short.toUnsignedInt(settings.controllerMask)
            var query = "&remoteControllersBitmap=$mask&gcmap=$mask&gcpersist=${if (settings.features.persistentGamepad) 1 else 0}"
            if (host?.capabilities?.virtualDisplayCapable == true) {
                when (settings.features.virtualDisplayMode) {
                    com.moonlight.material.core.models.VirtualDisplayMode.HOST_DEFAULT -> {}
                    com.moonlight.material.core.models.VirtualDisplayMode.VIRTUAL -> query += "&virtualDisplay=1"
                    com.moonlight.material.core.models.VirtualDisplayMode.PHYSICAL -> query += "&virtualDisplay=0"
                }
            }
            if (settings.enableHdr) {
                query += "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0"
            }
            return query
        }

        fun isValidUuid(uuidString: String): Boolean {
            return try {
                UUID.fromString(uuidString)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun isValidMacAddress(mac: String): Boolean {
            val parts = mac.split(":", "-")
            if (parts.size != 6) return false
            return parts.all { it.length == 2 && it.toIntOrNull(16) != null }
        }

        fun parseSimpleXml(xml: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            try {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                val root = doc.documentElement

                fun extractAttributesAndElements(element: Element) {
                    val attrs = element.attributes
                    for (i in 0 until attrs.length) {
                        val attr = attrs.item(i)
                        if (!map.containsKey(attr.nodeName)) {
                            map[attr.nodeName] = unescapeXml(attr.nodeValue)
                        }
                    }
                    val childNodes = element.childNodes
                    for (i in 0 until childNodes.length) {
                        val child = childNodes.item(i)
                        if (child.nodeType == Node.ELEMENT_NODE) {
                            val childElem = child as Element
                            var hasSubElement = false
                            val subNodes = childElem.childNodes
                            for (j in 0 until subNodes.length) {
                                if (subNodes.item(j).nodeType == Node.ELEMENT_NODE) {
                                    hasSubElement = true
                                    break
                                }
                            }
                            if (!hasSubElement) {
                                map[childElem.tagName] = unescapeXml(childElem.textContent.trim())
                            }
                            extractAttributesAndElements(childElem)
                        }
                    }
                }

                extractAttributesAndElements(root)
            } catch (e: Exception) {
                val innerTagRegex = Regex("<([a-zA-Z0-9_-]+)>([^<]*)</[a-zA-Z0-9_-]+>")
                for (match in innerTagRegex.findAll(xml)) {
                    val key = match.groupValues[1]
                    val value = unescapeXml(match.groupValues[2])
                    map[key] = value
                }
                val attributeRegex = Regex("([a-zA-Z0-9_-]+)=[\"']([^\"']*)[\"']")
                for (match in attributeRegex.findAll(xml)) {
                    val key = match.groupValues[1]
                    val value = unescapeXml(match.groupValues[2])
                    if (!map.containsKey(key)) {
                        map[key] = value
                    }
                }
            }
            return map
        }

        private fun unescapeXml(str: String): String {
            return str.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
        }

        fun parseAppListXml(xml: String, host: Host): List<StreamApp> {
            checkProtocolResponse(parseSimpleXml(xml))
            val apps = mutableListOf<StreamApp>()
            try {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                if (doc.documentElement.tagName != "root") throw IOException("Invalid applist response")
                val appNodes = doc.getElementsByTagName("App")
                val nodesToProcess = if (appNodes.length > 0) appNodes else doc.getElementsByTagName("app")
                for (i in 0 until nodesToProcess.length) {
                    val node = nodesToProcess.item(i) as? Element ?: continue
                    val title = (node.getElementsByTagName("AppTitle").item(0)?.textContent
                        ?: node.getElementsByTagName("apptitle").item(0)?.textContent
                        ?: "Unknown App").trim()
                    val idStr = (node.getElementsByTagName("ID").item(0)?.textContent
                        ?: node.getElementsByTagName("id").item(0)?.textContent)?.trim() ?: continue
                    val id = idStr.toIntOrNull() ?: continue
                    val isCollector = (node.getElementsByTagName("IsAppCollectorGame").item(0)?.textContent
                        ?: node.getElementsByTagName("isappcollectorgame").item(0)?.textContent)?.trim() == "1"
                    val uuid = (node.getElementsByTagName("UUID").item(0)?.textContent
                        ?: node.getElementsByTagName("AppUUID").item(0)?.textContent
                        ?: node.getElementsByTagName("uuid").item(0)?.textContent)?.trim()?.takeIf { isValidUuid(it) }
                    val hostOrder = (node.getElementsByTagName("IDX").item(0)?.textContent
                        ?: node.getElementsByTagName("idx").item(0)?.textContent)?.trim()?.toIntOrNull()
                    val artVersion = (node.getElementsByTagName("ArtVersion").item(0)?.textContent
                        ?: node.getElementsByTagName("artversion").item(0)?.textContent)?.trim()
                    val boxArtUrl = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/appasset?uniqueid=${host.serverId ?: "unknown"}&appid=$id&AssetType=2&AssetIdx=0"

                    apps.add(
                        StreamApp(
                            id = id,
                            title = title,
                            isAppCollectorGame = isCollector,
                            boxArtUrl = boxArtUrl,
                            uuid = uuid,
                            hostOrder = hostOrder,
                            artVersion = artVersion
                        )
                    )
                }
            } catch (e: Exception) {
                throw IOException("Failed to parse applist XML", e)
            }
            return apps
        }
    }
}
