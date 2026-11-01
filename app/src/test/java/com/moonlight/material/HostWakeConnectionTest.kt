package com.moonlight.material

import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostStatus
import com.moonlight.material.core.security.HostTrustException
import com.moonlight.material.networking.HostWakeConnection
import com.moonlight.material.networking.HostWakeFailure
import com.moonlight.material.networking.WakeOnLAN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class HostWakeConnectionTest {

    @Test
    fun awakeHostOpensWithoutWake() {
        runBlocking {
            val host = Host(name = "PC", address = "192.168.1.2", status = HostStatus.ONLINE)
            var wakes = 0
            val result = HostWakeConnection.resolve(
                host = host,
                probe = { it },
                wake = { wakes++ },
                onWaking = { wakes++ }
            )
            assertEquals(host.id, result.id)
            assertEquals(0, wakes)
        }
    }

    @Test
    fun sleepingHostWaitsForLiveServerInfo() {
        runBlocking {
            val host = Host(name = "PC", address = "192.168.1.2", status = HostStatus.OFFLINE, macAddress = "aa:bb:cc:dd:ee:ff")
            val events = mutableListOf<String>()
            var probes = 0
            val result = HostWakeConnection.resolve(
                host = host,
                probe = { current ->
                    events.add("probe")
                    probes++
                    // Even a successful probe returning offline metadata is not ready
                    if (probes < 3) current else current.copy(status = HostStatus.ONLINE)
                },
                wake = { mac ->
                    assertEquals(host.macAddress, mac)
                    events.add("wake")
                },
                pause = { events.add("wait") },
                onWaking = { events.add("waking") }
            )
            assertTrue(result.isOnline)
            assertEquals(listOf("probe", "waking", "wake", "wait", "probe", "wait", "probe"), events)
        }
    }

    @Test
    fun wakeNeedsSavedMAC() {
        val host = Host(name = "PC", address = "192.168.1.2")
        var wakes = 0
        assertThrows(HostWakeFailure.MissingMAC::class.java) {
            runBlocking {
                HostWakeConnection.resolve(
                    host = host,
                    probe = { throw IOException("Cannot connect") },
                    wake = { wakes++ },
                    onWaking = {}
                )
            }
        }
        assertEquals(0, wakes)
    }

    @Test
    fun cancelledWakeDoesNotPublishReadyHost() {
        val host = Host(name = "PC", address = "192.168.1.2", macAddress = "aa:bb:cc:dd:ee:ff")
        var probes = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                HostWakeConnection.resolve(
                    host = host,
                    probe = {
                        probes++
                        throw IOException("Cannot connect")
                    },
                    wake = {},
                    pause = { throw CancellationException("Cancelled") },
                    onWaking = {}
                )
            }
        }
        assertEquals(1, probes)
    }

    @Test
    fun wakeStopsAfterTimeoutAndPreservesTrustErrors() {
        val host = Host(name = "PC", address = "192.168.1.2", macAddress = "aa:bb:cc:dd:ee:ff")
        var probes = 0
        assertThrows(HostWakeFailure.TimedOut::class.java) {
            runBlocking {
                HostWakeConnection.resolve(
                    host = host,
                    attempts = 2,
                    probe = {
                        probes++
                        throw IOException("Cannot connect")
                    },
                    wake = {},
                    pause = {},
                    onWaking = {}
                )
            }
        }
        assertEquals(3, probes)

        var wakes = 0
        assertThrows(HostTrustException::class.java) {
            runBlocking {
                HostWakeConnection.resolve(
                    host = host,
                    probe = { throw HostTrustException("Unexpected host") },
                    wake = { wakes++ },
                    onWaking = {}
                )
            }
        }
        assertEquals(0, wakes)
    }

    @Test
    fun wakeDatagramSendsWithoutConnectionHandshake() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { socket ->
            val port = socket.localPort
            val payload = ByteArray(102) { 0xFF.toByte() }
            WakeOnLAN.send(payload, "127.0.0.1", port)

            val buffer = ByteArray(128)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.soTimeout = 1000
            socket.receive(packet)

            assertEquals(payload.size, packet.length)
            assertArrayEquals(payload, buffer.copyOf(packet.length))
        }
    }
}
