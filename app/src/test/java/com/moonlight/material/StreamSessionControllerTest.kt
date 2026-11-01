package com.moonlight.material

import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.core.state.StreamSessionController
import com.moonlight.material.core.utils.ConnectionDiagnostics
import com.moonlight.material.networking.SunshineHTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var controller: StreamSessionController

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val certManager = CertificateManager()
        val httpClient = SunshineHTTPClient(certManager)
        controller = StreamSessionController(httpClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStageTransitionsAndConnectedState() {
        assertFalse(controller.isStreaming.value)
        assertEquals(0, controller.currentStage.value)
        assertEquals("", controller.currentStageName.value)

        // Stage starting updates stage and name
        controller.onStageStarting(1)
        testDispatcher.scheduler.advanceUntilIdle()
        // stage 1 has not connected yet
        assertTrue(controller.currentStageName.value != "Connected")

        // Only onConnectionStarted transitions to "Connected"
        controller.onConnectionStarted()
        testDispatcher.scheduler.advanceUntilIdle()
        // If not streaming, markConnected does not fire
        // Let's test direct stage update while streaming
        controller.updateStage(4, "RTSP Handshake")
        // When not streaming, updateStage is ignored
        assertEquals(0, controller.currentStage.value)
    }

    @Test
    fun testStageFailureSetsDiagnosticsAndStopsSession() {
        controller.setStreamingForTesting(true)

        // Test stage failure diagnostic formatting
        val diagnostic = ConnectionDiagnostics.stageFailure(4, -1, "RTSP Handshake", "UDP 47998, UDP 47999")
        assertTrue(diagnostic.contains("RTSP Handshake failed (-1)"))
        assertTrue(diagnostic.contains("UDP 47998"))

        controller.handleStageFailed(4, -1, diagnostic)
        assertEquals(diagnostic, controller.sessionError.value)
        assertFalse(controller.isStreaming.value)
    }

    @Test
    fun testTerminationErrorMapping() {
        // Graceful termination (0) produces null error
        assertNull(ConnectionDiagnostics.termination(0))

        // No video traffic (-100) produces descriptive error
        val noVideoMsg = ConnectionDiagnostics.termination(ConnectionDiagnostics.ML_ERROR_NO_VIDEO_TRAFFIC)
        assertNotNull(noVideoMsg)
        assertTrue(noVideoMsg!!.contains("No video arrived"))

        // Frame conversion error (-104) produces HDR / resolution diagnostic
        val convMsg = ConnectionDiagnostics.termination(ConnectionDiagnostics.ML_ERROR_FRAME_CONVERSION)
        assertNotNull(convMsg)
        assertTrue(convMsg!!.contains("convert video"))

        controller.setStreamingForTesting(true)
        controller.handleTerminated(ConnectionDiagnostics.ML_ERROR_NO_VIDEO_TRAFFIC, noVideoMsg)
        assertEquals(noVideoMsg, controller.sessionError.value)
        assertFalse(controller.isStreaming.value)
    }

    @Test
    fun testNegotiatedStreamParametersInVideoSetup() {
        // Video format with HEVC (0x0100) and 4:4:4 (0x0400)
        val formatHevc444 = 0x0100 or 0x0400
        val ret = controller.videoBridgeListener.onSetup(formatHevc444, 2560, 1440, 120)
        assertEquals(0, ret)

        // AV1 (0x1000)
        val formatAv1 = 0x1000
        val retAv1 = controller.videoBridgeListener.onSetup(formatAv1, 3840, 2160, 60)
        assertEquals(0, retAv1)
    }

    @Test
    fun testConnectionStatusWarning() {
        controller.setStreamingForTesting(true)
        // status = 1 (CONN_STATUS_POOR)
        controller.updateConnectionStatus(1)
        assertEquals("Network quality is poor. Try lowering the bitrate.", controller.connectionWarning.value)

        // status = 0 (CONN_STATUS_OKAY)
        controller.updateConnectionStatus(0)
        assertNull(controller.connectionWarning.value)
    }
}
