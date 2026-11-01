package com.moonlight.material

import com.moonlight.material.core.models.AppControl
import com.moonlight.material.core.models.ClientFeatures
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostCapabilities
import com.moonlight.material.core.models.HostHardwareMetrics
import com.moonlight.material.core.models.HostStatus
import com.moonlight.material.core.models.HostXMLValues
import com.moonlight.material.core.models.OTPPairing
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.models.VirtualDisplayMode
import com.moonlight.material.networking.HostControlError
import com.moonlight.material.networking.SunshineHTTPClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.UUID

class HostControlClientTest {

    private fun extensionHost(): Host {
        val certB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        return Host(
            name = "Fixture",
            address = "127.0.0.1",
            serverId = "fixture",
            isPaired = true,
            serverCertificateDER = certB64,
            status = HostStatus.ONLINE
        )
    }

    @Test
    fun otpMatchesHostGoldenVectorWithoutSendingPassphrase() {
        val otp = OTPPairing(code = "0412", passphrase = "correct horse")
        assertTrue(otp.isValid)
        assertEquals(
            "ECC2CAF1B739532ACF7EB1DCA1E8FF11338530A65F3CD16CCF639CFB53BF547A",
            otp.authentication(saltHex = "00112233445566778899aabbccddeeff")
        )
        assertFalse(OTPPairing(code = "４１２０", passphrase = "correct horse").isValid)
        assertFalse(OTPPairing(code = "0412", passphrase = "").isValid)
    }

    @Test
    fun sleepUsesOneUseTokenAndNoLaunchOrExtraArguments() {
        runBlocking {
            val requests = mutableListOf<okhttp3.Request>()
            val client = SunshineHTTPClient(
                uniqueID = { "fixture" },
                extensionTransport = { request, _ ->
                    requests.add(request)
                    if (request.url.encodedPath == "/power-actions/v1") {
                        assertEquals("GET", request.method)
                        assertTrue(request.url.queryParameterNames.isEmpty())
                        val xml = "<root status_code=\"200\"><PowerActionVersion>1</PowerActionVersion><SleepSupported>1</SleepSupported><SleepAllowed>1</SleepAllowed><SleepAvailable>1</SleepAvailable><SleepToken>a+b/?&amp;</SleepToken></root>"
                        Pair(xml.toByteArray(Charsets.UTF_8), 200)
                    } else {
                        assertEquals("/power-actions/v1/sleep", request.url.encodedPath)
                        assertEquals("POST", request.method)
                        assertEquals("a+b/?&", request.url.queryParameter("token"))
                        val xml = "<root status_code=\"202\"><SleepAccepted>1</SleepAccepted></root>"
                        Pair(xml.toByteArray(Charsets.UTF_8), 202)
                    }
                }
            )
            client.sleepHost(extensionHost())
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun busySleepNeverPostsAndLostAcknowledgementNeverRetries() {
        runBlocking {
            val busyRequests = mutableListOf<okhttp3.Request>()
            val busy = SunshineHTTPClient(
                uniqueID = { "fixture" },
                extensionTransport = { request, _ ->
                    busyRequests.add(request)
                    val xml = "<root status_code=\"200\"><PowerActionVersion>1</PowerActionVersion><SleepSupported>1</SleepSupported><SleepAllowed>1</SleepAllowed><SleepAvailable>0</SleepAvailable></root>"
                    Pair(xml.toByteArray(Charsets.UTF_8), 200)
                }
            )
            assertThrows(HostControlError::class.java) {
                runBlocking { busy.sleepHost(extensionHost()) }
            }
            assertEquals(1, busyRequests.size)

            val lostRequests = mutableListOf<okhttp3.Request>()
            val lost = SunshineHTTPClient(
                uniqueID = { "fixture" },
                extensionTransport = { request, _ ->
                    lostRequests.add(request)
                    if (request.method == "POST") throw IOException("Timed out")
                    val xml = "<root status_code=\"200\"><PowerActionVersion>1</PowerActionVersion><SleepSupported>1</SleepSupported><SleepAllowed>1</SleepAllowed><SleepAvailable>1</SleepAvailable><SleepToken>once</SleepToken></root>"
                    Pair(xml.toByteArray(Charsets.UTF_8), 200)
                }
            )
            assertThrows(HostControlError::class.java) {
                runBlocking { lost.sleepHost(extensionHost()) }
            }
            assertEquals(2, lostRequests.size)
        }
    }

    @Test
    fun runtimeBitrateUsesHostAppliedValueAndSeparateABRCapability() {
        runBlocking {
            val client = SunshineHTTPClient(
                uniqueID = { "fixture" },
                extensionTransport = { request, _ ->
                    if (request.url.encodedPath == "/api/abr/capabilities") {
                        val json = """{"supported":false,"version":1,"features":["runtime_bitrate"]}"""
                        Pair(json.toByteArray(Charsets.UTF_8), 200)
                    } else {
                        assertEquals("/bitrate", request.url.encodedPath)
                        assertEquals("40000", request.url.queryParameter("bitrate"))
                        val xml = "<root status_code=\"200\"><bitrate>25000</bitrate></root>"
                        Pair(xml.toByteArray(Charsets.UTF_8), 200)
                    }
                }
            )
            assertTrue(client.supportsRuntimeBitrate(extensionHost()))
            assertEquals(25000, client.setRuntimeBitrate(extensionHost(), 40000))
            assertThrows(HostControlError::class.java) {
                runBlocking { client.setRuntimeBitrate(extensionHost(), 0) }
            }
        }
    }

    @Test
    fun repeatedCommandsPermissionsAndVirtualDisplayRequests() {
        val xml = "<root><ServerCommand>First &amp; second</ServerCommand><ServerCommand><![CDATA[Display <toggle>]]></ServerCommand></root>"
        val names = HostXMLValues.elements("ServerCommand", xml)
        assertEquals(listOf("First & second", "Display <toggle>"), names)

        val caps = HostCapabilities.parse(
            mapOf(
                "Permission" to ((1L shl 25) or (1L shl 20)).toString(),
                "VirtualDisplayCapable" to "true",
                "VirtualDisplayDriverReady" to "0"
            ),
            names
        )
        assertTrue(caps.canView && caps.canRunCommands && !caps.canLaunch)
        assertFalse(caps.virtualDisplayReady)
        assertTrue(HostCapabilities.parse(emptyMap(), emptyList()).canLaunch) // Legacy unknown, not denial

        val host = extensionHost().copy(capabilities = caps)
        val settings = StreamSettings(features = ClientFeatures(virtualDisplayMode = VirtualDisplayMode.PHYSICAL))
        assertTrue(SunshineHTTPClient.buildLaunchQuery(settings, host).contains("virtualDisplay=0"))
        assertFalse(SunshineHTTPClient.buildLaunchQuery(settings).contains("virtualDisplay"))
    }

    @Test
    fun appCatalogueRetainsUUIDOrderAndRevisionAcrossNumericIDChanges() {
        val host = extensionHost()
        val uuid = "ed713cab-1c4c-4f08-8cb3-961da3210001"
        val xml = "<root><App><ID>8</ID><UUID>$uuid</UUID><AppTitle>Z game</AppTitle><IDX>0</IDX><ArtVersion>2</ArtVersion></App><App><ID>2</ID><AppTitle>A game</AppTitle><IDX>1</IDX></App></root>"
        val apps = SunshineHTTPClient.parseAppListXml(xml, host)
        assertEquals(listOf("Z game", "A game"), apps.map { it.title })
        assertEquals(uuid, apps.first().uuid)
        assertEquals("2", apps.first().artVersion)
        val changed = apps[0].copy(id = 21)
        assertEquals(changed.stableIdentity, apps[0].stableIdentity)
        assertEquals(
            com.moonlight.material.core.utils.ShortcutBundleStore.identifier(host, changed),
            com.moonlight.material.core.utils.ShortcutBundleStore.identifier(host, apps[0])
        )
    }

    @Test
    fun telemetryDistinguishesMissingReadingsFromZero() {
        val jsonStr = """{"version":1,"sampled_at_unix_ms":1.0,"interval_ms":1000,"cpu":{},"gpus":[{"id":-3,"name":"GPU","metrics":{"gpu_usage_percent":0.0}}]}"""
        val sample = Json { ignoreUnknownKeys = true }.decodeFromString<HostHardwareMetrics>(jsonStr)
        assertNull(sample.cpu.cpu_usage_percent)
        assertEquals(0.0, sample.gpus[0].metrics["gpu_usage_percent"] ?: -1.0, 0.0001)
        assertNull(sample.gpus[0].metrics["gpu_temperature_c"])
    }

    @Test
    fun monitorActionsDoNotCreateVideoOrTreatConfirmationAsSuccess() {
        runBlocking {
            val action = StreamApp(
                id = 2147483502,
                title = "Disconnect Monitor",
                uuid = "9a1c5a25-58fe-40e0-b9aa-7d3f00000002"
            )
            assertTrue(action.isHostAction && action.control == AppControl.DISCONNECT_MONITOR)

            val requests = mutableListOf<okhttp3.Request>()
            val client = SunshineHTTPClient(
                uniqueID = { "fixture" },
                extensionTransport = { request, _ ->
                    requests.add(request)
                    assertEquals("/launch", request.url.encodedPath)
                    assertEquals(action.uuid, request.url.queryParameter("appuuid"))
                    assertEquals(32, request.url.queryParameter("rikey")?.length)
                    val xml = "<root status_code=\"410\" status_message=\"Remote Monitor disconnected successfully.\"/>"
                    Pair(xml.toByteArray(Charsets.UTF_8), 200)
                }
            )
            val result = client.performHostAction(extensionHost(), action)
            assertEquals("Remote Monitor disconnected successfully.", result)
            assertEquals(1, requests.size)

            val confirmation = SunshineHTTPClient(
                uniqueID = { "fixture" },
                extensionTransport = { _, _ ->
                    val xml = "<root status_code=\"410\" status_message=\"Confirm termination before trying again\"/>"
                    Pair(xml.toByteArray(Charsets.UTF_8), 200)
                }
            )
            assertThrows(HostControlError::class.java) {
                runBlocking { confirmation.performHostAction(extensionHost(), action) }
            }
        }
    }

    @Test
    fun reservedControlIDWinsOverConflictingUUIDAndRunningIdentityUsesUUID() {
        val conflict = StreamApp(
            id = 2147483504,
            title = "Stale label",
            uuid = "9a1c5a25-58fe-40e0-b9aa-7d3f00000005"
        )
        assertEquals(AppControl.TERMINATE, conflict.control)
        assertTrue(conflict.isHostAction)

        val host = extensionHost().copy(
            currentAppId = 1,
            currentAppUUID = "ED713CAB-1C4C-4F08-8CB3-961DA3210001"
        )
        val app = StreamApp(
            id = 99,
            title = "Renumbered",
            uuid = host.currentAppUUID?.lowercase()
        )
        assertTrue(app.matchesRunning(host))

        val otherApp = StreamApp(
            id = 1,
            title = "Different game",
            uuid = UUID.randomUUID().toString()
        )
        assertFalse(otherApp.matchesRunning(host))
    }
}
