package com.moonlight.material.bridge

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MoonBridgeTest {

    @Test
    fun testMoonBridgeClassLoadingAndListenerRegistration() {
        assertNotNull(MoonBridge)

        val testListener = object : MoonBridge.ConnectionListener {
            var started = false
            override fun onStageStarting(stage: Int) {}
            override fun onStageComplete(stage: Int) {}
            override fun onStageFailed(stage: Int, errorCode: Int) {}
            override fun onConnectionStarted() { started = true }
            override fun onConnectionTerminated(errorCode: Int) {}
        }

        MoonBridge.connectionListener = testListener
        MoonBridge.bridgeClConnectionStarted()
        org.junit.Assert.assertTrue(testListener.started)

        MoonBridge.connectionListener = null
        assertNull(MoonBridge.connectionListener)
    }

    @Test
    fun testVideoAndAudioListenerDispatch() {
        var setupCalled = false
        MoonBridge.videoDecoderListener = object : MoonBridge.VideoDecoderRendererListener {
            override fun onSetup(videoFormat: Int, width: Int, height: Int, redrawRate: Int): Int {
                setupCalled = true
                return 0
            }
            override fun onStart() {}
            override fun onStop() {}
            override fun onCleanup() {}
            override fun onSubmitDecodeUnit(
                frameData: ByteArray,
                length: Int,
                bufferType: Int,
                frameNumber: Int,
                frameType: Int,
                frameHostProcessingLatency: Char,
                receiveTimeUs: Long,
                enqueueTimeUs: Long
            ): Int = 0
        }

        val ret = MoonBridge.bridgeDrSetup(1, 1920, 1080, 60)
        org.junit.Assert.assertEquals(0, ret)
        org.junit.Assert.assertTrue(setupCalled)
        MoonBridge.videoDecoderListener = null

        var audioInitCalled = false
        MoonBridge.audioRendererListener = object : MoonBridge.AudioRendererListener {
            override fun onInit(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int {
                audioInitCalled = true
                return 0
            }
            override fun onStart() {}
            override fun onStop() {}
            override fun onCleanup() {}
            override fun onPlaySample(sampleData: ShortArray) {}
        }

        val audioRet = MoonBridge.bridgeArInit(0x302CA, 48000, 240)
        org.junit.Assert.assertEquals(0, audioRet)
        org.junit.Assert.assertTrue(audioInitCalled)
        MoonBridge.audioRendererListener = null
    }
}
