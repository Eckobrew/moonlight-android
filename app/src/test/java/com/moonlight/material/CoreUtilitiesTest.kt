package com.moonlight.material

import android.content.Context
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.utils.BoxArtManager
import com.moonlight.material.core.utils.GameArtworkSearchService
import com.moonlight.material.core.utils.RuntimeServiceStatus
import com.moonlight.material.networking.SunshineHTTPClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreUtilitiesTest {

    private lateinit var tempDir: File
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("boxart_test").toFile()
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
        mockWebServer.shutdown()
        RuntimeServiceStatus.clear()
    }

    @Test
    fun testRuntimeServiceStatusTracking() {
        RuntimeServiceStatus.updateStatus("mDNS", isRunning = true, details = "Active on wlan0")
        val status = RuntimeServiceStatus.getStatus("mDNS")
        assertNotNull(status)
        assertTrue(status!!.isRunning)
        assertNull(status.lastError)
        assertEquals("Active on wlan0", status.details)
        assertTrue(RuntimeServiceStatus.isServiceHealthy("mDNS"))

        RuntimeServiceStatus.updateStatus("AudioEngine", isRunning = false, lastError = "AudioTrack underrun")
        assertFalse(RuntimeServiceStatus.isServiceHealthy("AudioEngine"))
    }

    @Test
    fun testBoxArtKeyGeneration() {
        val host = Host(name = "Gaming-PC", address = "192.168.1.100", macAddress = "AA:BB:CC:DD:EE:FF")
        val app = StreamApp(id = 1234, title = "Hades", uuid = "hades-uuid-1234")

        val mockContext = object : ContextWrapperMock(tempDir) {}
        val boxArtManager = BoxArtManager(mockContext)

        val key = boxArtManager.cacheKey(host, app)
        assertTrue(key.endsWith(".jpg"))
        assertEquals(68, key.length) // 64 hex SHA256 chars + .jpg
    }

    @Test
    fun testGameArtworkSearchService() = runBlocking {
        val mockResponse = """
            {
                "items": [
                    {
                        "id": 1145360,
                        "name": "Hades",
                        "tiny_image": "https://cdn.steam.com/tiny.jpg"
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockResponse))

        val customClient = OkHttpClient.Builder().build()
        val service = GameArtworkSearchService(customClient)

        // Empty search returns empty list
        val emptyResults = service.searchArtwork("")
        assertTrue(emptyResults.isEmpty())
    }
}

open class ContextWrapperMock(private val cacheDirectory: File) : android.content.ContextWrapper(null) {
    override fun getCacheDir(): File = cacheDirectory
}
