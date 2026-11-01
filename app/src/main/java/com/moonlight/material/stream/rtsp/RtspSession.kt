package com.moonlight.material.stream.rtsp

import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

data class RtspHandshakeResult(
    val sessionId: String,
    val videoPort: Int,
    val audioPort: Int,
    val controlPort: Int
)

class RtspSession(
    private val hostAddress: String,
    private val rtspPort: Int = 48010
) {
    private val TAG = "RTSP"
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var cSeq = 1
    private var sessionId: String? = null

    suspend fun performHandshake(): RtspHandshakeResult = withContext(Dispatchers.IO) {
        AppLogger.info("Connecting RTSP to $hostAddress:$rtspPort...", TAG)
        val s = Socket()
        s.connect(InetSocketAddress(hostAddress, rtspPort), 4000)
        s.soTimeout = 5000
        socket = s

        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)

        // 1. OPTIONS
        sendRequest("OPTIONS * RTSP/1.0", emptyMap())
        readResponse()

        // 2. DESCRIBE
        sendRequest("DESCRIBE rtsp://$hostAddress/ RTSP/1.0", mapOf("Accept" to "application/sdp"))
        readResponse()

        // 3. SETUP video
        sendRequest(
            "SETUP rtsp://$hostAddress/streamid=video RTSP/1.0",
            mapOf("Transport" to "unicast;X-GS-ClientPort=47998-47999")
        )
        val videoResp = readResponse()
        val videoPort = parseServerPort(videoResp["Transport"]) ?: 47998

        // 4. SETUP audio
        sendRequest(
            "SETUP rtsp://$hostAddress/streamid=audio RTSP/1.0",
            mapOf("Transport" to "unicast;X-GS-ClientPort=48000-48001")
        )
        val audioResp = readResponse()
        val audioPort = parseServerPort(audioResp["Transport"]) ?: 48000

        // 5. SETUP control
        sendRequest(
            "SETUP rtsp://$hostAddress/streamid=control RTSP/1.0",
            mapOf("Transport" to "unicast;X-GS-ClientPort=47999")
        )
        val controlResp = readResponse()
        val controlPort = parseServerPort(controlResp["Transport"]) ?: 47999

        // 6. PLAY
        sendRequest("PLAY rtsp://$hostAddress/ RTSP/1.0", emptyMap())
        readResponse()

        AppLogger.info("RTSP handshake successful! Video: $videoPort, Audio: $audioPort, Control: $controlPort", TAG)
        RtspHandshakeResult(
            sessionId = sessionId ?: "1",
            videoPort = videoPort,
            audioPort = audioPort,
            controlPort = controlPort
        )
    }

    private fun sendRequest(requestLine: String, headers: Map<String, String>) {
        val w = writer ?: return
        val currentCSeq = cSeq++
        val sb = StringBuilder()
        sb.append(requestLine).append("\r\n")
        sb.append("CSeq: ").append(currentCSeq).append("\r\n")
        sessionId?.let { sb.append("Session: ").append(it).append("\r\n") }
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("User-Agent: Moonlight-Android/0.2.0\r\n")
        sb.append("\r\n")

        w.write(sb.toString())
        w.flush()
    }

    private fun readResponse(): Map<String, String> {
        val r = reader ?: return emptyMap()
        val statusLine = r.readLine() ?: throw RuntimeException("RTSP EOF from server")
        if (!statusLine.contains("200 OK")) {
            AppLogger.warn("RTSP response: $statusLine", TAG)
        }

        val headers = mutableMapOf<String, String>()
        var line: String? = r.readLine()
        while (!line.isNullOrEmpty()) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
                if (key.equals("Session", ignoreCase = true)) {
                    sessionId = value.split(';')[0].trim()
                }
            }
            line = r.readLine()
        }
        return headers
    }

    private fun parseServerPort(transport: String?): Int? {
        if (transport == null) return null
        val match = Regex("server_port=([0-9]+)").find(transport)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun teardown() {
        try {
            if (sessionId != null) {
                sendRequest("TEARDOWN rtsp://$hostAddress/ RTSP/1.0", emptyMap())
            }
        } catch (e: Exception) {
            // Ignore teardown error on shutdown
        } finally {
            try { socket?.close() } catch (e: Exception) {}
            socket = null
            reader = null
            writer = null
        }
    }
}
