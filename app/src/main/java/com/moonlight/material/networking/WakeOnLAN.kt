package com.moonlight.material.networking

import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLAN {
    private const val TAG = "WOL"

    suspend fun wake(macAddress: String): Boolean = withContext(Dispatchers.IO) {
        val cleanMac = macAddress.replace(":", "").replace("-", "").trim()
        if (cleanMac.length != 12) {
            AppLogger.error("Invalid MAC address: $macAddress", TAG)
            return@withContext false
        }

        try {
            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                macBytes[i] = cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            // Magic Packet: 6 bytes of 0xFF followed by MAC address repeated 16 times
            val packetData = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) {
                packetData[i] = 0xFF.toByte()
            }
            for (i in 0 until 16) {
                System.arraycopy(macBytes, 0, packetData, 6 + i * 6, 6)
            }

            DatagramSocket().use { socket ->
                socket.broadcast = true
                val broadcastAddresses = listOf(
                    InetAddress.getByName("255.255.255.255"),
                    InetAddress.getByName("224.0.0.1")
                )

                val ports = intArrayOf(7, 9)
                for (address in broadcastAddresses) {
                    for (port in ports) {
                        val packet = DatagramPacket(packetData, packetData.size, address, port)
                        socket.send(packet)
                    }
                }
            }

            AppLogger.info("Sent Wake-on-LAN magic packet to $macAddress", TAG)
            true
        } catch (e: Exception) {
            AppLogger.error("Failed to send Wake-on-LAN packet: ${e.message}", TAG)
            false
        }
    }
}
