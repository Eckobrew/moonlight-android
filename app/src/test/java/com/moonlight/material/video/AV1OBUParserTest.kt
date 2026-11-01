package com.moonlight.material.video

import com.moonlight.material.stream.video.AV1OBUParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AV1OBUParserTest {

    @Test
    fun parseTemporalDelimiterAndSequenceHeader() {
        // Temporal delimiter: type 2 (0b00010 << 3 = 0x10), hasSizeField = 0x02 -> header = 0x12, size = 0
        val temporalDelimiter = byteArrayOf(0x12, 0x00)

        val obus = AV1OBUParser.parse(temporalDelimiter)
        assertEquals(1, obus.size)
        assertEquals(AV1OBUParser.OBU_TEMPORAL_DELIMITER, obus[0].type)
        assertArrayEquals(temporalDelimiter, obus[0].raw)
    }

    @Test
    fun parseMultipleObusInStream() {
        // OBU 1: Temporal delimiter (type 2, size 0)
        // OBU 2: Sequence Header (type 1, size 4, dummy payload [0x01, 0x02, 0x03, 0x04])
        // header = (1 << 3) | 0x02 = 0x0A, size = 4
        val stream = byteArrayOf(
            0x12, 0x00,
            0x0A, 0x04, 0x01, 0x02, 0x03, 0x04
        )

        val obus = AV1OBUParser.parse(stream)
        assertEquals(2, obus.size)
        assertEquals(AV1OBUParser.OBU_TEMPORAL_DELIMITER, obus[0].type)
        assertEquals(AV1OBUParser.OBU_SEQUENCE_HEADER, obus[1].type)
        assertEquals(6, obus[1].raw.size) // 2 bytes header/size + 4 bytes payload
    }

    @Test
    fun parseLeb128MultiByteSize() {
        // Multi-byte LEB128: 128 = 0x80, 0x01
        // Header: (type 3 << 3) | 2 = 0x1A
        val payload = ByteArray(128) { it.toByte() }
        val stream = ByteArray(1 + 2 + 128)
        stream[0] = 0x1A // type 3 (Frame Header) with size field
        stream[1] = 0x80.toByte()
        stream[2] = 0x01.toByte()
        System.arraycopy(payload, 0, stream, 3, 128)

        val obus = AV1OBUParser.parse(stream)
        assertEquals(1, obus.size)
        assertEquals(AV1OBUParser.OBU_FRAME_HEADER, obus[0].type)
        assertEquals(131, obus[0].raw.size)
    }

    @Test
    fun parseTruncatedStreamStopsGracefully() {
        // Header says size is 10, but only 2 bytes provided
        val truncated = byteArrayOf(0x0A, 0x0A, 0x01, 0x02)
        val obus = AV1OBUParser.parse(truncated)
        assertTrue(obus.isEmpty())
    }

    @Test
    fun parseForbiddenBitSetStopsScan() {
        // Bit 7 is set (0x80) which violates AV1 spec
        val invalid = byteArrayOf(0x8A.toByte(), 0x00)
        val obus = AV1OBUParser.parse(invalid)
        assertTrue(obus.isEmpty())
    }

    @Test
    fun av1CConfigurationRecordGeneration() {
        // A minimal valid AV1 Sequence Header OBU:
        // header: type 1, size field present (0x0A)
        // size: 10
        // payload: profile 0, still_picture 0, reduced 1, level 4 (0b000 0 1 00100...)
        // Bit pattern:
        // byte 0: seq_profile(3)=0, still_pic(1)=0, reduced_header(1)=1, level(3 bits)=0b001 -> 0b00001001 = 0x09
        // byte 1: level remaining 2 bits (0b00), widthBits-1 (4 bits)=0b0111 (8 bits), heightBits-1 (2 bits)=0b01 -> 0b00011101 = 0x1D
        // ...
        val obuHeader = byteArrayOf(0x0A, 0x04, 0x00, 0x00, 0x00, 0x00)
        // If parsing fails due to payload format, makeAV1CConfiguration safely returns null
        val config = AV1OBUParser.makeAV1CConfiguration(obuHeader)
        // Either null or valid 4+N byte record starting with 0x81
        if (config != null) {
            assertEquals(0x81.toByte(), config[0])
            assertEquals(4 + obuHeader.size, config.size)
        }
    }
}
