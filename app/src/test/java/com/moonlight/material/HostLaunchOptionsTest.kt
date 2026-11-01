package com.moonlight.material

import com.moonlight.material.core.models.ClientFeatures
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostCapabilities
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.models.VirtualDisplayMode
import com.moonlight.material.networking.SunshineHTTPClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from CommonCFeatureTests.swift:42-56 and HostExtensionTests.swift:80-92.
 */
class HostLaunchOptionsTest {

    @Test
    fun testLaunchOptionsKeepControllerAndHdrConsistent() {
        val settings = StreamSettings(
            controllerMask = 0x8001.toShort(),
            features = ClientFeatures(persistentGamepad = true)
        )

        val normalQuery = SunshineHTTPClient.buildLaunchQuery(settings)
        assertTrue(normalQuery.contains("remoteControllersBitmap=32769"))
        assertTrue(normalQuery.contains("gcmap=32769&gcpersist=1"))
        assertFalse(normalQuery.contains("hdrMode="))

        val hdrSettings = settings.copy(enableHdr = true)
        val hdrQuery = SunshineHTTPClient.buildLaunchQuery(hdrSettings)
        assertTrue(hdrQuery.contains("hdrMode=1"))
    }

    @Test
    fun testVirtualDisplayQueryParameters() {
        val hostWithVirtualDisplay = Host(
            name = "TestHost",
            address = "192.168.1.100",
            capabilities = HostCapabilities(virtualDisplayCapable = true)
        )
        val hostWithoutVirtualDisplay = Host(
            name = "LegacyHost",
            address = "192.168.1.101",
            capabilities = HostCapabilities(virtualDisplayCapable = false)
        )

        val settingsPhysical = StreamSettings(
            features = ClientFeatures(virtualDisplayMode = VirtualDisplayMode.PHYSICAL)
        )
        val settingsVirtual = StreamSettings(
            features = ClientFeatures(virtualDisplayMode = VirtualDisplayMode.VIRTUAL)
        )
        val settingsDefault = StreamSettings(
            features = ClientFeatures(virtualDisplayMode = VirtualDisplayMode.HOST_DEFAULT)
        )

        // Host capable: physical mode -> virtualDisplay=0
        val physicalQuery = SunshineHTTPClient.buildLaunchQuery(settingsPhysical, hostWithVirtualDisplay)
        assertTrue(physicalQuery.contains("virtualDisplay=0"))

        // Host capable: virtual mode -> virtualDisplay=1
        val virtualQuery = SunshineHTTPClient.buildLaunchQuery(settingsVirtual, hostWithVirtualDisplay)
        assertTrue(virtualQuery.contains("virtualDisplay=1"))

        // Host capable: host default -> no virtualDisplay param
        val defaultQuery = SunshineHTTPClient.buildLaunchQuery(settingsDefault, hostWithVirtualDisplay)
        assertFalse(defaultQuery.contains("virtualDisplay"))

        // Host NOT capable: no virtualDisplay param regardless of setting
        val legacyQuery = SunshineHTTPClient.buildLaunchQuery(settingsVirtual, hostWithoutVirtualDisplay)
        assertFalse(legacyQuery.contains("virtualDisplay"))

        // No host provided -> no virtualDisplay param
        val noHostQuery = SunshineHTTPClient.buildLaunchQuery(settingsVirtual)
        assertFalse(noHostQuery.contains("virtualDisplay"))
    }
}
