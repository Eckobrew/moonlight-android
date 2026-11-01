package com.moonlight.material.video

import com.moonlight.material.stream.video.StreamHDRState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamHDRStateTest {

    private fun createSampleHdrMetadata(): ByteArray {
        // 26-byte SS_HDR_METADATA:
        // displayPrimaries[3] (6 shorts)
        // whitePoint (2 shorts)
        // maxDisplayLuminance (1 short)
        // minDisplayLuminance (1 short)
        // maxContentLightLevel (1 short)
        // maxFrameAverageLightLevel (1 short)
        // maxFullFrameLuminance (1 short)
        val buffer = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        // Primaries for DCI-P3: R(34000, 16000), G(13250, 34500), B(7500, 3000)
        buffer.putShort(34000.toShort()) // Rx
        buffer.putShort(16000.toShort()) // Ry
        buffer.putShort(13250.toShort()) // Gx
        buffer.putShort(34500.toShort()) // Gy
        buffer.putShort(7500.toShort())  // Bx
        buffer.putShort(3000.toShort())  // By
        // White point: D65 (15635, 16450)
        buffer.putShort(15635.toShort())
        buffer.putShort(16450.toShort())
        // Luminance
        buffer.putShort(1000.toShort()) // Max display: 1000 nits
        buffer.putShort(50.toShort())   // Min display: 0.005 nits (50 / 10000)
        buffer.putShort(1000.toShort()) // MaxCLL: 1000 nits
        buffer.putShort(400.toShort())  // MaxFALL: 400 nits
        buffer.putShort(800.toShort())  // Max full frame
        return buffer.array()
    }

    @Test
    fun initialStateIsDisabled() {
        val hdrState = StreamHDRState()
        assertFalse(hdrState.isHdrEnabled)
        assertNull(hdrState.currentHdrMetadata)
        assertNull(hdrState.createHdrStaticInfo())
    }

    @Test
    fun updateWithValidMetadataEnablesHdr() {
        val hdrState = StreamHDRState()
        val metadata = createSampleHdrMetadata()

        val changed = hdrState.update(enabled = true, metadata = metadata)
        assertTrue(changed)
        assertTrue(hdrState.isHdrEnabled)
        assertNotNull(hdrState.currentHdrMetadata)

        val staticInfo = hdrState.createHdrStaticInfo()
        assertNotNull(staticInfo)
        assertEquals(25, staticInfo!!.remaining())

        // Verify CTA-861.3 InfoFrame content
        assertEquals(0.toByte(), staticInfo.get()) // Type 0

        // Display primaries Rx, Ry
        assertEquals(34000.toShort(), staticInfo.short)
        assertEquals(16000.toShort(), staticInfo.short)
        // Gx, Gy
        assertEquals(13250.toShort(), staticInfo.short)
        assertEquals(34500.toShort(), staticInfo.short)
        // Bx, By
        assertEquals(7500.toShort(), staticInfo.short)
        assertEquals(3000.toShort(), staticInfo.short)
        // White X, Y
        assertEquals(15635.toShort(), staticInfo.short)
        assertEquals(16450.toShort(), staticInfo.short)
        // Max/Min luminance
        assertEquals(1000.toShort(), staticInfo.short)
        assertEquals(50.toShort(), staticInfo.short)
        // MaxCLL, MaxFALL
        assertEquals(1000.toShort(), staticInfo.short)
        assertEquals(400.toShort(), staticInfo.short)
    }

    @Test
    fun updateWithIdenticalMetadataReturnsFalse() {
        val hdrState = StreamHDRState()
        val metadata = createSampleHdrMetadata()

        assertTrue(hdrState.update(enabled = true, metadata = metadata))
        // Updating with identical metadata should return false (no reconfigure needed)
        assertFalse(hdrState.update(enabled = true, metadata = metadata))
    }

    @Test
    fun updateWithDisabledClearsMetadata() {
        val hdrState = StreamHDRState()
        val metadata = createSampleHdrMetadata()

        hdrState.update(enabled = true, metadata = metadata)
        val changed = hdrState.update(enabled = false, metadata = null)

        assertTrue(changed)
        assertFalse(hdrState.isHdrEnabled)
        assertNull(hdrState.currentHdrMetadata)
        assertNull(hdrState.createHdrStaticInfo())
    }
}
