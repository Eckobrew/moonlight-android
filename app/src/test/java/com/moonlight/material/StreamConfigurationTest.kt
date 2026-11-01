package com.moonlight.material

import com.moonlight.material.core.models.AudioConfigurationMode
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.models.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class StreamConfigurationTest {

    @Test
    fun testDefaultSettingsAreValid() {
        val settings = StreamSettings()
        assertNull(settings.validationMessage)
        assertEquals(1920, settings.width)
        assertEquals(1080, settings.height)
        assertEquals(60, settings.fps)
        assertEquals(30, settings.bitrateMbps)
    }

    @Test
    fun testRemoteBitrateCalculation() {
        val settings = StreamSettings(
            bitrateMbps = 40,
            enableRemoteBitrate = true,
            remoteBitrateMbps = 0 // 0 means automatic 25%
        )
        // 40 Mbps normal -> 10 Mbps remote (10,000 Kbps)
        assertEquals(10000, settings.streamBitrateKbps(isRemote = true))
        assertEquals(40000, settings.streamBitrateKbps(isRemote = false))

        val manualRemote = settings.copy(remoteBitrateMbps = 15)
        assertEquals(15000, manualRemote.streamBitrateKbps(isRemote = true))
    }

    @Test
    fun testValidationCatchesInvalidResolutions() {
        val oddWidth = StreamSettings(width = 1921, height = 1080)
        assertNotNull(oddWidth.validationMessage)

        val outOfBoundsFps = StreamSettings(fps = 0)
        assertNotNull(outOfBoundsFps.validationMessage)
    }

    @Test
    fun testLimelightMasks() {
        val autoMask = VideoCodec.AUTO.limelightMask(enableHdr = false)
        assertTrue((autoMask and 0x0001) != 0) // H264
        assertTrue((autoMask and 0x0002) != 0) // HEVC

        val hdrMask = VideoCodec.HEVC.limelightMask(enableHdr = true)
        assertTrue((hdrMask and 0x0004) != 0) // HEVC Main10

        val audioStereo = AudioConfigurationMode.STEREO.limelightValue
        assertEquals(2, (audioStereo shr 8) and 0xFF) // 2 channels
    }
}
