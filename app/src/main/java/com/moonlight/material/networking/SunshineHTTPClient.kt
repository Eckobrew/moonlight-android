package com.moonlight.material.networking

import android.util.Base64
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostCapabilities
import com.moonlight.material.core.models.HostStatus
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.core.security.HostTrust
import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class SunshineHTTPClient(
    private val certificateManager: CertificateManager
) {
    private val baseHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val pairingHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // Stage 1 waits for user PIN entry
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Cache of authenticated HTTPS OkHttpClients pinned to each host's serverCertificateDER
    private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun getClientForHost(host: Host, forPairing: Boolean = false): OkHttpClient {
        val certDerBase64 = host.serverCertificateDER
        if (certDerBase64.isNullOrEmpty()) {
            return if (forPairing) pairingHttpClient else baseHttpClient
        }

        return pinnedClients.getOrPut(certDerBase64) {
            val der = Base64.decode(certDerBase64, Base64.DEFAULT)
            val (sslSocketFactory, trustManager) = HostTrust.createPinnedSSLSocketFactory(der, certificateManager)
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .sslSocketFactory(sslSocketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // Pinned certificate validates identity
                .build()
        }
    }

    suspend fun fetchRaw(
        urlString: String,
        timeoutSec: Long = 10,
        certificateDER: ByteArray? = null,
        usePairingSession: Boolean = false
    ): Pair<Int, Map<String, String>> = withContext(Dispatchers.IO) {
        val client = if (certificateDER != null) {
            val (sslSocketFactory, trustManager) = HostTrust.createPinnedSSLSocketFactory(certificateDER, certificateManager)
            OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .sslSocketFactory(sslSocketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
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

    suspend fun fetchServerInfo(host: Host): Pair<Host, Map<String, String>> = withContext(Dispatchers.IO) {
        val uniqueId = certificateManager.uniqueId
        var responseData: String? = null
        var isHttps = false

        // Try HTTPS first if certificate is known and port reachable
        if (host.isSecurelyPaired && host.httpsPort > 0) {
            try {
                val httpsUrl = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/serverinfo?uniqueid=$uniqueId"
                val client = getClientForHost(host)
                val request = Request.Builder().url(httpsUrl).build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            responseData = body
                            isHttps = true
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.debug("HTTPS serverinfo failed for ${host.name}: ${e.message}", "HTTP")
            }
        }

        // Fallback to HTTP
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
        val pairStatus = dict["PairStatus"]
        val isConfirmedPaired = host.isSecurelyPaired || (isHttps && pairStatus == "1")

        val status = if (isConfirmedPaired) {
            val curGame = dict["currentgame"]?.toIntOrNull() ?: 0
            if (curGame != 0) HostStatus.STREAMING else HostStatus.ONLINE
        } else {
            HostStatus.PAIRING_REQUIRED
        }

        val updatedHost = host.copy(
            name = dict["hostname"] ?: host.name,
            status = status,
            isPaired = isConfirmedPaired || host.needsCertificatePairing,
            currentAppId = dict["currentgame"]?.toIntOrNull()?.takeIf { it != 0 },
            currentAppUUID = dict["currentgameuuid"],
            serverVersion = dict["appversion"] ?: host.serverVersion,
            gfeVersion = dict["GfeVersion"] ?: host.gfeVersion,
            appVersion = dict["appversion"] ?: host.appVersion,
            macAddress = dict["mac"] ?: host.macAddress,
            serverId = dict["uniqueid"] ?: host.serverId,
            serverCodecModeSupport = dict["ServerCodecModeSupport"]?.toIntOrNull() ?: host.serverCodecModeSupport,
            lastSeenEpochMs = System.currentTimeMillis()
        )

        Pair(updatedHost, dict)
    }

    suspend fun fetchAppList(host: Host): List<StreamApp> = withContext(Dispatchers.IO) {
        val uniqueId = certificateManager.uniqueId
        val url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/applist?uniqueid=$uniqueId"
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw IOException("Client is not authorized. Please pair with this host first.")
            }
            if (!response.isSuccessful) {
                throw IOException("applist returned status ${response.code}")
            }
            val body = response.body?.string() ?: return@withContext emptyList()
            parseAppListXml(body, host)
        }
    }

    suspend fun fetchBoxArt(host: Host, appId: Int, appUUID: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val uniqueId = certificateManager.uniqueId
        var url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/appasset?uniqueid=$uniqueId&appid=$appId&AssetType=2&AssetIdx=0"
        if (!appUUID.isNullOrEmpty()) {
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
        rikeyId: Long
    ): String? = withContext(Dispatchers.IO) {
        val uniqueId = certificateManager.uniqueId
        val mode = "${settings.width}x${settings.height}x${settings.fps}"
        val surroundAudioInfo = settings.audioMode.surroundAudioInfo
        val localAudioPlayMode = if (settings.muteHostAudio) 0 else 1
        val sops = if (settings.optimizeGameSettings) "1" else "0"

        var url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/launch?uniqueid=$uniqueId&appid=$appId&mode=$mode&additionalStates=1&sops=$sops&rikey=$rikey&rikeyid=$rikeyId&localAudioPlayMode=$localAudioPlayMode&surroundAudioInfo=$surroundAudioInfo"
        if (!appUUID.isNullOrEmpty()) {
            url += "&appuuid=$appUUID"
        }
        url += buildLaunchQuery(settings, host)

        AppLogger.info("Launching app $appId on ${host.name} ($mode)", "HTTP")
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Launch failed with HTTP ${response.code}")
            }
            val dict = parseSimpleXml(response.body?.string() ?: "")
            val statusCode = dict["status_code"] ?: "200"
            if (statusCode != "200") {
                val msg = dict["status_message"] ?: "Launch failed (code $statusCode)"
                throw IOException(msg)
            }
            dict["sessionUrl0"]
        }
    }

    suspend fun resumeApp(
        host: Host,
        settings: StreamSettings,
        rikey: String,
        rikeyId: Long
    ): String? = withContext(Dispatchers.IO) {
        val uniqueId = certificateManager.uniqueId
        val surroundAudioInfo = settings.audioMode.surroundAudioInfo
        val localAudioPlayMode = if (settings.muteHostAudio) 0 else 1

        var url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/resume?uniqueid=$uniqueId&rikey=$rikey&rikeyid=$rikeyId&localAudioPlayMode=$localAudioPlayMode&surroundAudioInfo=$surroundAudioInfo"
        url += buildLaunchQuery(settings, host)

        AppLogger.info("Resuming session on ${host.name}", "HTTP")
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
        val uniqueId = certificateManager.uniqueId
        val url = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/cancel?uniqueid=$uniqueId"
        val client = getClientForHost(host)
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun unpair(host: Host): Boolean = withContext(Dispatchers.IO) {
        val uniqueId = certificateManager.uniqueId
        val url = "http://${host.urlAddress}:${host.effectiveHTTPPort}/unpair?uniqueid=$uniqueId"
        try {
            baseHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun buildLaunchQuery(settings: StreamSettings, host: Host): String {
        var query = "&remoteControllersBitmap=${settings.controllerMask}&gcmap=${settings.controllerMask}&gcpersist=${if (settings.features.persistentGamepad) 1 else 0}"
        if (settings.enableHdr) {
            query += "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0"
        }
        return query
    }

    companion object {
        fun parseSimpleXml(xml: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            try {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                val root = doc.documentElement
                parseElementRecursive(root, map)
            } catch (e: Exception) {
                // If DOM fails, parse tags by regex
                val pattern = Regex("<([a-zA-Z0-9_-]+)>([^<]*)</[a-zA-Z0-9_-]+>")
                for (match in pattern.findAll(xml)) {
                    val key = match.groupValues[1]
                    val value = match.groupValues[2]
                    map[key] = value
                }
            }
            return map
        }

        private fun parseElementRecursive(node: Node, map: MutableMap<String, String>) {
            val childNodes = node.childNodes
            for (i in 0 until childNodes.length) {
                val child = childNodes.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val element = child as Element
                    val text = element.textContent.trim()
                    map[element.tagName] = text
                    parseElementRecursive(child, map)
                }
            }
        }

        fun parseAppListXml(xml: String, host: Host): List<StreamApp> {
            val apps = mutableListOf<StreamApp>()
            try {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                val appNodes = doc.getElementsByTagName("App")
                for (i in 0 until appNodes.length) {
                    val node = appNodes.item(i) as? Element ?: continue
                    val title = node.getElementsByTagName("AppTitle").item(0)?.textContent?.trim() ?: "Unknown"
                    val idStr = node.getElementsByTagName("ID").item(0)?.textContent?.trim() ?: continue
                    val id = idStr.toIntOrNull() ?: continue
                    val isCollector = node.getElementsByTagName("IsAppCollectorGame").item(0)?.textContent?.trim() == "1"
                    val uuid = node.getElementsByTagName("AppUUID").item(0)?.textContent?.trim()

                    val boxArtUrl = "https://${host.urlAddress}:${host.effectiveHTTPSPort}/appasset?uniqueid=${host.serverId ?: ""}&appid=$id&AssetType=2&AssetIdx=0"
                    apps.add(StreamApp(id = id, title = title, isAppCollectorGame = isCollector, boxArtUrl = boxArtUrl, uuid = uuid))
                }
            } catch (e: Exception) {
                AppLogger.error("Failed to parse app list XML: ${e.message}", "HTTP")
            }
            return apps
        }
    }
}
