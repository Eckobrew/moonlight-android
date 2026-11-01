package com.moonlight.material

import com.moonlight.material.core.models.AudioConfigurationMode
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.models.VideoCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Port of StreamConfigurationTests.swift:codecMasksRespectHdrPreference
     */
    @Test
    fun codecMasksRespectHdrPreference() {
        val videoFormatMask10Bit = 0x0200 or 0x2000 // HEVC Main10, AV1 Main10
        val videoFormatH265Main10 = 0x0200

        for (codec in listOf(VideoCodec.AUTO, VideoCodec.H264, VideoCodec.HEVC)) {
            val mask = codec.limelightMask(enableHdr = false)
            assertEquals(0, mask and videoFormatMask10Bit)
        }

        val hevcHdrMask = VideoCodec.HEVC.limelightMask(enableHdr = true)
        assertTrue((hevcHdrMask and videoFormatH265Main10) != 0)
    }

    @Test
    fun codecMasksMatchBundledNativeLibrary() {
        assertEquals(0x1101, VideoCodec.AUTO.limelightMask())
        assertEquals(0x0100, VideoCodec.HEVC.limelightMask())
        assertEquals(0x1000, VideoCodec.AV1.limelightMask())
        assertEquals(0x0300, VideoCodec.HEVC.limelightMask(enableHdr = true))
        assertEquals(0x3000, VideoCodec.AV1.limelightMask(enableHdr = true))
        assertEquals(0x0005, VideoCodec.H264.limelightMask(yuv444 = true))
        assertEquals(0x0F00, VideoCodec.HEVC.limelightMask(enableHdr = true, yuv444 = true))
    }

    @Test
    fun testLimelightMasks() {
        val autoMask = VideoCodec.AUTO.limelightMask(enableHdr = false)
        assertTrue((autoMask and 0x0001) != 0) // H264
        assertTrue((autoMask and 0x0100) != 0) // HEVC

        val audioStereo = AudioConfigurationMode.STEREO.limelightValue
        assertEquals(2, (audioStereo shr 8) and 0xFF) // 2 channels
    }

    @Test
    fun remoteAndMeteredConnectionsUseRemoteBitrate() {
        val settings = StreamSettings(bitrateMbps = 40, enableRemoteBitrate = true, remoteBitrateMbps = 12)
        val local = com.moonlight.material.core.models.Host(name = "PC", address = "192.168.1.10")
        val remote = local.copy(remoteAddress = "my-pc.example", activeAddress = "my-pc.example")
        assertEquals(40000, settings.streamBitrateKbps(local.isRemoteConnection || false))
        assertEquals(12000, settings.streamBitrateKbps(local.isRemoteConnection || true))
        assertEquals(12000, settings.streamBitrateKbps(remote.isRemoteConnection || false))
        assertTrue(local.copy(address = "8.8.8.8").isRemoteConnection)
        assertFalse(local.copy(address = "100.64.0.1").isRemoteConnection)
    }

    @Test
    fun nativeResolutionUsesEvenLandscapeDisplayDimensions() {
        val native = StreamSettings(width = 0, height = 0)
        assertNull(native.validationMessage)
        val resolved = native.resolveNativeResolution(1081, 2401)
        assertEquals(2400, resolved.width)
        assertEquals(1080, resolved.height)
        assertNull(resolved.validationMessage)
        assertEquals(1920, StreamSettings().resolveNativeResolution(1080, 2400).width)
        assertNotNull(StreamSettings(width = 0, height = 1080).validationMessage)
    }
}
