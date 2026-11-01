package com.moonlight.material.stream.video

/**
 * AV1 Open Bitstream Unit (OBU) parser and configuration builder.
 *
 * Ported from Sources/MoonlightMac/Pipeline/Video/AV1OBUParser.swift.
 * Walks the OBU bitstream, parses Sequence Header OBUs, extracts coded dimensions,
 * and generates ISO-BMFF av1C configuration records.
 */
object AV1OBUParser {

    data class OBU(
        val type: Byte,
        val raw: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as OBU
            if (type != other.type) return false
            return raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }

    // OBU type values (AV1 spec §5.3.2)
    const val OBU_SEQUENCE_HEADER: Byte = 1
    const val OBU_TEMPORAL_DELIMITER: Byte = 2
    const val OBU_FRAME_HEADER: Byte = 3
    const val OBU_TILE_GROUP: Byte = 4
    const val OBU_METADATA: Byte = 5
    const val OBU_FRAME: Byte = 6
    const val OBU_REDUNDANT_FRAME_HEADER: Byte = 7
    const val OBU_TILE_LIST: Byte = 8
    const val OBU_PADDING: Byte = 15

    /**
     * Walks the OBU stream and returns every OBU it can parse.
     * A malformed or truncated unit stops the scan rather than guessing.
     */
    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<OBU> {
        val obus = mutableListOf<OBU>()
        var curOffset = offset
        val endLimit = offset + length

        while (curOffset < endLimit) {
            if (curOffset + 1 > endLimit) break

            val header = data[curOffset].toInt() and 0xFF
            if ((header and 0x80) != 0) break // forbidden bit must be zero

            val type = ((header shr 3) and 0x0F).toByte()
            val hasExtension = (header and 0x04) != 0
            val hasSizeField = (header and 0x02) != 0

            var headerLength = 1
            if (hasExtension) headerLength += 1

            val payloadLength: Int
            if (hasSizeField) {
                // LEB128 payload size
                var size = 0
                var shift = 0
                var sizeBytes = 0
                var index = curOffset + headerLength
                var valid = false
                while (index < endLimit) {
                    val byte = data[index].toInt() and 0xFF
                    size = size or ((byte and 0x7F) shl shift)
                    shift += 7
                    sizeBytes++
                    index++
                    if ((byte and 0x80) == 0) {
                        valid = true
                        break
                    }
                    if (sizeBytes > 8) return obus
                }
                if (!valid && index >= endLimit) break
                headerLength += sizeBytes
                payloadLength = size
            } else {
                // Without a size field the OBU runs to the end of the frame
                payloadLength = endLimit - curOffset - headerLength
            }

            val payloadStart = curOffset + headerLength
            val end = payloadStart + payloadLength
            if (payloadLength < 0 || end > endLimit) break

            val rawBytes = data.copyOfRange(curOffset, end)
            obus.add(OBU(type = type, raw = rawBytes))
            curOffset = end
        }

        return obus
    }

    /**
     * Builds the av1C AV1CodecConfigurationRecord (AV1 ISOBMFF binding §2.3.3).
     *
     * configOBUs stores the complete Sequence Header OBU including its header
     * and LEB128 size field.
     */
    fun makeAV1CConfiguration(sequenceHeader: ByteArray): ByteArray? {
        if (sequenceHeader.size < 2) return null
        val headerType = ((sequenceHeader[0].toInt() and 0xFF) shr 3) and 0x0F
        if (headerType.toByte() != OBU_SEQUENCE_HEADER) return null

        val payloadStart = obuHeaderInfo(sequenceHeader) ?: return null
        if (payloadStart >= sequenceHeader.size) return null

        val payload = sequenceHeader.copyOfRange(payloadStart, sequenceHeader.size)
        val fields = parseSequenceHeader(payload) ?: return null

        val config = ByteArray(4 + sequenceHeader.size)
        config[0] = 0x81.toByte() // marker = 1, version = 1

        // seq_profile (3) | seq_level_idx_0 (5)
        config[1] = (((fields.seqProfile.toInt() and 0x07) shl 5) or (fields.level.toInt() and 0x1F)).toByte()

        // seq_tier_0(1) high_bitdepth(1) twelve_bit(1) monochrome(1)
        // chroma_subsampling_x(1) chroma_subsampling_y(1) chroma_sample_position(2)
        var b2 = 0
        b2 = b2 or ((fields.tier.toInt() and 0x01) shl 7)
        b2 = b2 or ((fields.highBitDepth.toInt() and 0x01) shl 6)
        b2 = b2 or ((fields.twelveBit.toInt() and 0x01) shl 5)
        b2 = b2 or ((fields.monochrome.toInt() and 0x01) shl 4)
        b2 = b2 or ((fields.subsamplingX.toInt() and 0x01) shl 3)
        b2 = b2 or ((fields.subsamplingY.toInt() and 0x01) shl 2)
        b2 = b2 or (fields.chromaSamplePosition.toInt() and 0x03)
        config[2] = b2.toByte()

        // reserved(3) | initial_presentation_delay_present(1)=0 | reserved(4)=0
        config[3] = 0x00

        // configOBUs: the Sequence Header OBU itself
        System.arraycopy(sequenceHeader, 0, config, 4, sequenceHeader.size)
        return config
    }

    /**
     * Coded dimensions from the sequence header (max_frame_width / max_frame_height).
     */
    fun sequenceHeaderDimensions(sequenceHeader: ByteArray): Pair<Int, Int>? {
        val payloadStart = obuHeaderInfo(sequenceHeader) ?: return null
        if (payloadStart >= sequenceHeader.size) return null

        val payload = sequenceHeader.copyOfRange(payloadStart, sequenceHeader.size)
        val fields = parseSequenceHeader(payload) ?: return null
        if (fields.maxFrameWidth <= 0 || fields.maxFrameHeight <= 0) return null
        return Pair(fields.maxFrameWidth, fields.maxFrameHeight)
    }

    /**
     * Index of the OBU payload: after header, optional extension, and LEB128 size field.
     */
    private fun obuHeaderInfo(raw: ByteArray): Int? {
        if (raw.isEmpty()) return null
        val header = raw[0].toInt() and 0xFF
        var index = 1
        if ((header and 0x04) != 0) { // obu_extension_flag
            index += 1
        }
        if ((header and 0x02) == 0) {
            // No size field: remainder is payload
            return index
        }
        var sizeBytes = 0
        while (index < raw.size) {
            val byte = raw[index].toInt() and 0xFF
            index++
            sizeBytes++
            if ((byte and 0x80) == 0) break
            if (sizeBytes > 8) return null
        }
        return index
    }

    private data class SequenceFields(
        var seqProfile: Byte = 0,
        var level: Byte = 0,
        var tier: Byte = 0,
        var highBitDepth: Byte = 0,
        var twelveBit: Byte = 0,
        var monochrome: Byte = 0,
        var subsamplingX: Byte = 0,
        var subsamplingY: Byte = 0,
        var chromaSamplePosition: Byte = 0,
        var maxFrameWidth: Int = 0,
        var maxFrameHeight: Int = 0
    )

    private fun parseSequenceHeader(payload: ByteArray): SequenceFields? {
        val reader = BitReader(payload)
        val f = SequenceFields()

        val profile = reader.read(3) ?: return null
        reader.read(1) ?: return null // still_picture
        val reduced = reader.read(1) ?: return null // reduced_still_picture_header
        f.seqProfile = profile.toByte()

        var frameIDNumbersPresent = 0

        if (reduced == 1) {
            val level = reader.read(5) ?: return null
            f.level = level.toByte()
            f.tier = 0
        } else {
            val timingInfoPresent = reader.read(1) ?: return null
            var decoderModelInfoPresent = 0
            var bufferDelayLengthMinus1 = 0

            if (timingInfoPresent == 1) {
                reader.skip(64) ?: return null
                val equalInterval = reader.read(1) ?: return null
                if (equalInterval == 1) {
                    reader.skipUVLC() ?: return null
                }
                val dmi = reader.read(1) ?: return null
                decoderModelInfoPresent = dmi
                if (dmi == 1) {
                    val delayLen = reader.read(5) ?: return null
                    reader.skip(32 + 5 + 5) ?: return null
                    bufferDelayLengthMinus1 = delayLen
                }
            }

            val initialDelayPresent = reader.read(1) ?: return null
            val opCountMinus1 = reader.read(5) ?: return null
            val opCount = opCountMinus1 + 1

            for (i in 0 until opCount) {
                reader.skip(12) ?: return null // operating_point_idc
                val level = reader.read(5) ?: return null
                if (i == 0) f.level = level.toByte()

                if (level > 7) {
                    val tier = reader.read(1) ?: return null
                    if (i == 0) f.tier = tier.toByte()
                }

                if (decoderModelInfoPresent == 1) {
                    val present = reader.read(1) ?: return null
                    if (present == 1) {
                        val n = bufferDelayLengthMinus1 + 1
                        reader.skip(2 * n + 1) ?: return null
                    }
                }

                if (initialDelayPresent == 1) {
                    val present = reader.read(1) ?: return null
                    if (present == 1) {
                        reader.read(4) ?: return null
                    }
                }
            }

            val present = reader.read(1) ?: return null
            frameIDNumbersPresent = present
        }

        val widthBitsMinus1 = reader.read(4) ?: return null
        val heightBitsMinus1 = reader.read(4) ?: return null
        val maxW = reader.read(widthBitsMinus1 + 1) ?: return null
        val maxH = reader.read(heightBitsMinus1 + 1) ?: return null
        f.maxFrameWidth = maxW + 1
        f.maxFrameHeight = maxH + 1

        if (frameIDNumbersPresent == 1) {
            reader.skip(7) ?: return null
        }

        reader.read(1) ?: return null // use_128x128_superblock
        reader.read(1) ?: return null // enable_filter_intra
        reader.read(1) ?: return null // enable_intra_edge_filter

        var enableOrderHint = 0

        if (reduced == 0) {
            reader.read(1) ?: return null // enable_interintra_compound
            reader.read(1) ?: return null // enable_masked_compound
            reader.read(1) ?: return null // enable_warped_motion
            reader.read(1) ?: return null // enable_dual_filter
            val orderHint = reader.read(1) ?: return null
            enableOrderHint = orderHint

            if (enableOrderHint == 1) {
                reader.skip(2) ?: return null
            }

            val chooseScreenTools = reader.read(1) ?: return null
            if (chooseScreenTools == 0) {
                val forceScreenTools = reader.read(1) ?: return null
                if (forceScreenTools > 0) {
                    val chooseIntegerMV = reader.read(1) ?: return null
                    if (chooseIntegerMV == 0) {
                        reader.read(1) ?: return null
                    }
                }
            }

            if (enableOrderHint == 1) {
                reader.skip(3) ?: return null
            }
        }

        reader.read(1) ?: return null // enable_superres
        reader.read(1) ?: return null // enable_cdef
        reader.read(1) ?: return null // enable_restoration

        // color_config()
        val highBitDepth = reader.read(1) ?: return null
        f.highBitDepth = highBitDepth.toByte()

        val bitDepth: Int
        if (f.seqProfile.toInt() == 2 && highBitDepth == 1) {
            val twelve = reader.read(1) ?: return null
            f.twelveBit = twelve.toByte()
            bitDepth = if (twelve == 1) 12 else 10
        } else {
            bitDepth = if (highBitDepth == 1) 10 else 8
        }

        if (f.seqProfile.toInt() == 1) {
            f.monochrome = 0
        } else {
            val mono = reader.read(1) ?: return null
            f.monochrome = mono.toByte()
        }

        val hasColorDescription = reader.read(1) ?: return null
        if (hasColorDescription == 1) {
            reader.skip(24) ?: return null
        }

        if (f.monochrome.toInt() == 1) {
            reader.read(1) ?: return null // color_range
            f.subsamplingX = 1
            f.subsamplingY = 1
            f.chromaSamplePosition = 0
            return f
        }

        reader.read(1) ?: return null // color_range

        when (f.seqProfile.toInt()) {
            0 -> {
                f.subsamplingX = 1
                f.subsamplingY = 1
            }
            1 -> {
                f.subsamplingX = 0
                f.subsamplingY = 0
            }
            else -> {
                if (bitDepth == 12) {
                    val sx = reader.read(1) ?: return null
                    f.subsamplingX = sx.toByte()
                    if (sx == 1) {
                        val sy = reader.read(1) ?: return null
                        f.subsamplingY = sy.toByte()
                    } else {
                        f.subsamplingY = 0
                    }
                } else {
                    f.subsamplingX = 1
                    f.subsamplingY = 0
                }
            }
        }

        if (f.subsamplingX.toInt() == 1 && f.subsamplingY.toInt() == 1) {
            val csp = reader.read(2) ?: return null
            f.chromaSamplePosition = csp.toByte()
        }

        return f
    }

    /**
     * MSB-first bit reader for AV1 headers.
     */
    private class BitReader(private val bytes: ByteArray) {
        private var bitIndex = 0

        private val remaining: Int
            get() = bytes.size * 8 - bitIndex

        private fun canRead(count: Int): Boolean = count in 0..remaining

        fun read(count: Int): Int? {
            if (!canRead(count)) return null
            var value = 0
            for (i in 0 until count) {
                val byte = bytes[bitIndex shr 3].toInt() and 0xFF
                val bit = (byte shr (7 - (bitIndex and 7))) and 1
                value = (value shl 1) or bit
                bitIndex++
            }
            return value
        }

        fun skip(count: Int): Boolean? {
            if (!canRead(count)) return null
            bitIndex += count
            return true
        }

        fun skipUVLC(): Boolean? {
            var leadingZeros = 0
            while (true) {
                val bit = read(1) ?: return null
                if (bit == 1) break
                leadingZeros++
                if (leadingZeros > 32) return null
            }
            return skip(leadingZeros)
        }
    }
}
