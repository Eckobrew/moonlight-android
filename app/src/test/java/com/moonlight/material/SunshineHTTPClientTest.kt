package com.moonlight.material

import com.moonlight.material.core.models.AudioConfigurationMode
import com.moonlight.material.networking.SunshineHTTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SunshineHTTPClientTest {

    @Test
    fun testUuidValidation() {
        val validUuid = UUID.randomUUID().toString()
        assertTrue(SunshineHTTPClient.isValidUuid(validUuid))
        assertTrue(SunshineHTTPClient.isValidUuid("ed713cab-1c4c-4f08-8cb3-961da3210001"))

        assertFalse(SunshineHTTPClient.isValidUuid("not-a-uuid"))
        assertFalse(SunshineHTTPClient.isValidUuid(""))
        assertFalse(SunshineHTTPClient.isValidUuid("12345"))
    }

    @Test
    fun testMacAddressValidation() {
        assertTrue(SunshineHTTPClient.isValidMacAddress("00:1A:2B:3C:4D:5E"))
        assertTrue(SunshineHTTPClient.isValidMacAddress("00-1a-2b-3c-4d-5e"))

        assertFalse(SunshineHTTPClient.isValidMacAddress("00:1A:2B:3C:4D"))
        assertFalse(SunshineHTTPClient.isValidMacAddress("00:1A:2B:3C:4D:5E:6F"))
        assertFalse(SunshineHTTPClient.isValidMacAddress("invalid-mac"))
        assertFalse(SunshineHTTPClient.isValidMacAddress(""))
    }

    @Test
    fun testSurroundAudioInfoComputation() {
        // Stereo: channelCount=2, channelMask=0x3 -> (0x3 << 16) | 2 = 0x00030002
        assertEquals(0x00030002, AudioConfigurationMode.STEREO.surroundAudioInfo)

        // 5.1 Surround: channelCount=6, channelMask=0x3F -> (0x3F << 16) | 6 = 0x003F0006
        assertEquals(0x003F0006, AudioConfigurationMode.SURROUND_51.surroundAudioInfo)

        // 7.1 Surround: channelCount=8, channelMask=0x63F -> (0x63F << 16) | 8 = 0x063F0008
        assertEquals(0x063F0008, AudioConfigurationMode.SURROUND_71.surroundAudioInfo)
    }

    @Test
    fun testParseSimpleXml() {
        val xml = "<root status_code=\"200\"><sessionUrl0>rtsp://192.168.1.100:48010</sessionUrl0><gamesession>1</gamesession></root>"
        val dict = SunshineHTTPClient.parseSimpleXml(xml)
        assertEquals("rtsp://192.168.1.100:48010", dict["sessionUrl0"])
        assertEquals("1", dict["gamesession"])
    }

    @Test(expected = java.io.IOException::class)
    fun appListRejectsProtocolErrorInsteadOfReturningEmpty() {
        SunshineHTTPClient.parseAppListXml(
            "<root status_code=\"401\" status_message=\"Client not authorized\"/>",
            com.moonlight.material.core.models.Host(name = "PC", address = "localhost")
        )
    }

    @Test(expected = java.io.IOException::class)
    fun malformedAppListDoesNotEraseCatalogue() {
        SunshineHTTPClient.parseAppListXml(
            "<html>error</html>",
            com.moonlight.material.core.models.Host(name = "PC", address = "localhost")
        )
    }

    @Test
    fun validEmptyCatalogueIsAccepted() {
        assertTrue(SunshineHTTPClient.parseAppListXml(
            "<root status_code=\"200\"/>",
            com.moonlight.material.core.models.Host(name = "PC", address = "localhost")
        ).isEmpty())
    }
}
