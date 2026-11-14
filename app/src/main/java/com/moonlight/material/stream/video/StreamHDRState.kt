package com.moonlight.material.stream.video

import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * Manages HDR state and CTA-861.3 static metadata for MediaFormat configuration.
 *
 * Ported from Sources/MoonlightMac/Pipeline/Video/StreamHDRState.swift and adapted
 * for Android MediaCodec / CTA-861.3 InfoFrame format.
 */
class StreamHDRState {
    private val lock = Any()

    var isHdrEnabled: Boolean = false
        private set

    var currentHdrMetadata: ByteArray? = null
        private set

    /**
     * Updates the HDR state.
     * Returns true if the configuration has changed (requiring codec restart/update).
     */
    fun update(enabled: Boolean, metadata: ByteArray?): Boolean {
        synchronized(lock) {
            val wasEnabled = isHdrEnabled
            val oldMetadata = currentHdrMetadata

            if (!enabled || metadata == null) {
                isHdrEnabled = false
                currentHdrMetadata = null
                return wasEnabled || oldMetadata != null
            }

            val changed = !wasEnabled || !Arrays.equals(oldMetadata, metadata)
            isHdrEnabled = true
            currentHdrMetadata = metadata.clone()
            return changed
        }
    }

    /**
     * Creates the CTA-861.3 HDMI Dynamic Range and Mastering InfoFrame
     * required by MediaFormat.KEY_HDR_STATIC_INFO.
     */
    fun createHdrStaticInfo(): ByteBuffer? {
        synchronized(lock) {
            val metadata = currentHdrMetadata ?: return null
            if (metadata.size < 24) return null

            val hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            val hdrMetadata = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN)

            // CTA-861.3 InfoFrame Type 0
            hdrStaticInfo.put(0.toByte()) // Metadata type 0

            // Display Primaries (RGB order, 6 shorts = 12 bytes)
            hdrStaticInfo.putShort(hdrMetadata.short) // RX
            hdrStaticInfo.putShort(hdrMetadata.short) // RY
            hdrStaticInfo.putShort(hdrMetadata.short) // GX
            hdrStaticInfo.putShort(hdrMetadata.short) // GY
            hdrStaticInfo.putShort(hdrMetadata.short) // BX
            hdrStaticInfo.putShort(hdrMetadata.short) // BY

            // White Point (2 shorts = 4 bytes)
            hdrStaticInfo.putShort(hdrMetadata.short) // White X
            hdrStaticInfo.putShort(hdrMetadata.short) // White Y

            // Luminance info (4 shorts = 8 bytes)
            hdrStaticInfo.putShort(hdrMetadata.short) // Max display mastering luminance
            hdrStaticInfo.putShort(hdrMetadata.short) // Min display mastering luminance
            hdrStaticInfo.putShort(hdrMetadata.short) // Max content light level (MaxCLL)
            hdrStaticInfo.putShort(hdrMetadata.short) // Max frame average light level (MaxFALL)

            hdrStaticInfo.rewind()
            return hdrStaticInfo
        }
    }

    /**
     * Applies color range, color characteristics, and HDR static info to a MediaFormat.
     * Matches upstream MediaCodecDecoderRenderer.createBaseMediaFormat:
     * - Sets KEY_COLOR_RANGE (full vs limited)
     * - For 8-bit SDR streams, explicitly sets COLOR_TRANSFER_SDR_VIDEO and BT709/BT601
     * - For 10-bit streams, omits hardcoded SDR color standard/transfer so bitstream VUI
     *   controls HDR transitions, and attaches KEY_HDR_STATIC_INFO when metadata is present.
     */
    fun applyToMediaFormat(
        format: MediaFormat,
        videoFormat: Int = 0,
        fullRange: Boolean = false,
        colorSpace: Int = com.moonlight.material.bridge.MoonBridge.COLORSPACE_REC_709
    ) {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(
                    MediaFormat.KEY_COLOR_RANGE,
                    if (fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED
                )

                val is10Bit = (videoFormat and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0
                if (!is10Bit && !isHdrEnabled) {
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    val colorStandard = when (colorSpace) {
                        com.moonlight.material.bridge.MoonBridge.COLORSPACE_REC_601 -> MediaFormat.COLOR_STANDARD_BT601_NTSC
                        com.moonlight.material.bridge.MoonBridge.COLORSPACE_REC_709 -> MediaFormat.COLOR_STANDARD_BT709
                        com.moonlight.material.bridge.MoonBridge.COLORSPACE_REC_2020 -> MediaFormat.COLOR_STANDARD_BT2020
                        else -> MediaFormat.COLOR_STANDARD_BT709
                    }
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO)
                    }
                } else {
                    val staticInfo = createHdrStaticInfo()
                    if (staticInfo != null) {
                        format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, staticInfo)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO)
                    }
                }
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            isHdrEnabled = false
            currentHdrMetadata = null
        }
    }
}
