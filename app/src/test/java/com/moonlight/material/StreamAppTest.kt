package com.moonlight.material

import com.moonlight.material.core.models.AppControl
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Ported from HostExtensionTests.swift:94-105, 152-166.
 */
class StreamAppTest {

    @Test
    fun testReservedControlIdWinsOverConflictingUuidAndRunningIdentityUsesUuid() {
        val conflict = StreamApp(
            id = 2147483504,
            title = "Stale label",
            uuid = "9a1c5a25-58fe-40e0-b9aa-7d3f00000005"
        )
        // Numeric control ID 2147483504 maps to TERMINATE
        assertEquals(AppControl.TERMINATE, conflict.control)
        assertTrue(conflict.isHostAction)

        val targetUuid = "ED713CAB-1C4C-4F08-8CB3-961DA3210001"
        val host = Host(
            name = "Fixture",
            address = "127.0.0.1",
            currentAppId = 1,
            currentAppUUID = targetUuid
        )

        val matchingApp = StreamApp(
            id = 99,
            title = "Renumbered",
            uuid = targetUuid.lowercase()
        )
        // UUID matches (case-insensitive) even though id (99) != host.currentAppId (1)
        assertTrue(matchingApp.matchesRunning(host))

        val nonMatchingApp = StreamApp(
            id = 1,
            title = "Different game",
            uuid = UUID.randomUUID().toString()
        )
        // Different UUID must NOT match running host app, even if id (1) == host.currentAppId (1)
        assertFalse(nonMatchingApp.matchesRunning(host))
    }

    @Test
    fun testMatchesRunningNumericFallback() {
        val hostWithApp1 = Host(
            name = "Fixture",
            address = "127.0.0.1",
            currentAppId = 42,
            currentAppUUID = null
        )

        val appWithoutUuid = StreamApp(id = 42, title = "Game")
        assertTrue(appWithoutUuid.matchesRunning(hostWithApp1))

        val appOtherId = StreamApp(id = 99, title = "Other Game")
        assertFalse(appOtherId.matchesRunning(hostWithApp1))

        // Host with currentAppId = 0 (no app running) must never match
        val hostIdle = Host(
            name = "IdleHost",
            address = "127.0.0.1",
            currentAppId = 0,
            currentAppUUID = null
        )
        assertFalse(StreamApp(id = 0, title = "Desktop").matchesRunning(hostIdle))
        assertFalse(appWithoutUuid.matchesRunning(hostIdle))
    }

    @Test
    fun testVirtualMonitorUuidDetection() {
        val monitorApp = StreamApp(
            id = 100,
            title = "Virtual Display",
            uuid = "9a1c5a25-58fe-40e0-b9aa-7d3f00000005"
        )
        assertEquals(AppControl.MONITOR, monitorApp.control)
        assertTrue(monitorApp.isRemoteMonitor)

        val disconnectApp = StreamApp(
            id = 101,
            title = "Disconnect Monitor",
            uuid = "9a1c5a25-58fe-40e0-b9aa-7d3f00000002"
        )
        assertEquals(AppControl.DISCONNECT_MONITOR, disconnectApp.control)
        assertTrue(disconnectApp.isHostAction)
    }
}
