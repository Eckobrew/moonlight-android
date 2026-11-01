package com.moonlight.material.networking

import com.moonlight.material.core.security.CryptoUtils
import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

object WakeOnLAN {
    private const val TAG = "WOL"

    /**
     * Sends a Wake-on-LAN magic packet.
     * Matches WakeOnLAN.swift:9-45
     */
    suspend fun wake(
        macAddress: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanMac = macAddress.replace(":", "").replace("-", "").trim()
        if (cleanMac.length != 12) {
            AppLogger.error("Invalid MAC address: $macAddress", TAG)
            return@withContext false
        }

        val macBytes = CryptoUtils.dataFromHex(cleanMac)
        if (macBytes == null || macBytes.size != 6) {
            AppLogger.error("Failed to decode MAC hex: $cleanMac", TAG)
            return@withContext false
        }

        try {
            // Magic packet: 6 bytes of 0xFF followed by 16 repetitions of MAC address (102 bytes)
            val packetData = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) {
                packetData[i] = 0xFF.toByte()
            }
            for (i in 0 until 16) {
                System.arraycopy(macBytes, 0, packetData, 6 + i * 6, 6)
            }

            val destinations = mutableListOf(broadcastAddress)
            for (address in subnetBroadcastAddresses()) {
                if (!destinations.contains(address)) {
                    destinations.add(address)
                }
            }
            if (!destinations.contains("255.255.255.255")) {
                destinations.add("255.255.255.255")
            }
            if (!destinations.contains("224.0.0.1")) {
                destinations.add("224.0.0.1")
            }

            var sent = false
            var lastError: Exception? = null
            val ports = if (port == 9) intArrayOf(9, 7) else intArrayOf(port)

            for (destination in destinations) {
                coroutineContext.ensureActive()
                for (p in ports) {
                    try {
                        send(packetData, destination, p)
                        sent = true
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }

            if (sent) {
                AppLogger.info("Sent Wake-on-LAN magic packet to $macAddress across ${destinations.size} destinations", TAG)
                true
            } else {
                if (lastError != null) {
                    AppLogger.error("Failed to send Wake-on-LAN packet: ${lastError.message}", TAG)
                }
                false
            }
        } catch (e: Exception) {
            AppLogger.error("Failed to assemble or send Wake-on-LAN packet: ${e.message}", TAG)
            false
        }
    }

    /**
     * Nonblocking datagram send without connection handshake.
     * Matches WakeOnLAN.swift:49-74
     */
    fun send(packetData: ByteArray, to: String, port: Int) {
        val address = InetAddress.getByName(to)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val packet = DatagramPacket(packetData, packetData.size, address, port)
            socket.send(packet)
        }
    }

    /**
     * Computes directed broadcast addresses for active non-loopback IPv4 interfaces.
     * Matches WakeOnLAN.swift:78-119
     */
    fun subnetBroadcastAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        val host = broadcast.hostAddress
                        if (!host.isNullOrEmpty() && host != "0.0.0.0" && !addresses.contains(host)) {
                            addresses.add(host)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.error("Failed to enumerate subnet broadcast addresses: ${e.message}", TAG)
        }
        return addresses
    }
}
