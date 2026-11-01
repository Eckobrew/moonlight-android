package com.moonlight.material.networking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object TCPProbe {
    suspend fun canConnect(address: String, port: Int, timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
