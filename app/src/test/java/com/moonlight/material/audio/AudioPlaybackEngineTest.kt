package com.moonlight.material.audio

import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.stream.audio.AudioPlaybackEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackEngineTest {

    @Test
    fun audioConfigurationChannelCountExtraction() {
        assertEquals(2, MoonBridge.channelCountFromAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_STEREO))
        assertEquals(6, MoonBridge.channelCountFromAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_51_SURROUND))
        assertEquals(8, MoonBridge.channelCountFromAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_71_SURROUND))
    }

    @Test
    fun audioConfigurationChannelMaskExtraction() {
        assertEquals(0x3, MoonBridge.channelMaskFromAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_STEREO))
        assertEquals(0x3F, MoonBridge.channelMaskFromAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_51_SURROUND))
        assertEquals(0x63F, MoonBridge.channelMaskFromAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_71_SURROUND))
    }

    @Test
    fun audioPlaybackEngineLifecycleAndMuting() {
        val engine = AudioPlaybackEngine(muteOnFocusLoss = true)
        engine.configure(muteOnFocusLoss = true, appFocused = false)

        // Focus changes
        engine.updateFocus(true)
        engine.setMuted(true)
        engine.setMuted(false)
        engine.updateFocus(false)

        engine.stop()
        engine.cleanup()
    }
}
