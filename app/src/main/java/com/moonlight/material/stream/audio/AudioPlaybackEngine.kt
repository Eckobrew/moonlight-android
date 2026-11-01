package com.moonlight.material.stream.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.core.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance low-latency audio playback engine for streaming sessions.
 *
 * Implements Android AudioTrack streaming supporting Stereo, Quad, 5.1, and 7.1 surround sound.
 * Features:
 * - Low-latency mode configuration (PERFORMANCE_MODE_LOW_LATENCY / FLAG_LOW_LATENCY).
 * - Dynamic buffer sizing with optimal frame quantum.
 * - Latency-bounding queue protection: drops packets when moonlight-common-c's pending
 *   audio duration exceeds 40ms to avoid audio backlog lag.
 * - Focus management and background muting that survives engine recreation.
 */
class AudioPlaybackEngine(
    private var muteOnFocusLoss: Boolean = true
) : AudioOutputBackend {

    private val TAG = "AudioPlaybackEngine"

    private var audioTrack: AudioTrack? = null
    private val isTrackRunning = AtomicBoolean(false)
    private var isMuted = false
    private var appFocused = true

    override val isRunning: Boolean
        get() = isTrackRunning.get()

    private var negotiatedChannelCount: Int = 2
    private var negotiatedSampleRate: Int = 48000
    private var negotiatedSamplesPerFrame: Int = 240
    private var channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO

    private val lock = Any()

    fun init(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int {
        synchronized(lock) {
            disposeInternal()

            val extractedChannels = MoonBridge.channelCountFromAudioConfiguration(audioConfiguration)
            negotiatedChannelCount = if (extractedChannels > 0) extractedChannels else 2
            negotiatedSampleRate = if (sampleRate > 0) sampleRate else 48000
            negotiatedSamplesPerFrame = if (samplesPerFrame > 0) samplesPerFrame else 240

            channelMask = when (negotiatedChannelCount) {
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                4 -> AudioFormat.CHANNEL_OUT_QUAD
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                8 -> 0x000018fc // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> AudioFormat.CHANNEL_OUT_STEREO
            }

            return initializeAudioTrack()
        }
    }

    private fun initializeAudioTrack(): Int {
        val bytesPerFrame = negotiatedChannelCount * negotiatedSamplesPerFrame * 2

        // Try low-latency options first, falling back to standard buffer sizes
        val attempts = listOf(
            Pair(true, bytesPerFrame * 2), // small buffer, low latency
            Pair(true, Math.max(AudioTrack.getMinBufferSize(negotiatedSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT), bytesPerFrame * 2)),
            Pair(false, bytesPerFrame * 2),
            Pair(false, Math.max(AudioTrack.getMinBufferSize(negotiatedSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT), bytesPerFrame * 2))
        )

        for ((lowLatency, rawBufferSize) in attempts) {
            val bufferSize = ((rawBufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame

            try {
                val track = createAudioTrack(negotiatedSampleRate, channelMask, bufferSize, lowLatency)
                if (isMuted || (muteOnFocusLoss && !appFocused)) {
                    track.setVolume(0.0f)
                } else {
                    track.setVolume(1.0f)
                }

                audioTrack = track
                AppLogger.info("AudioTrack initialized: ${negotiatedChannelCount}ch @ ${negotiatedSampleRate}Hz (buffer: ${bufferSize}b, lowLatency: $lowLatency)", TAG)
                return 0
            } catch (e: Exception) {
                AppLogger.warn("Failed AudioTrack attempt ($bufferSize bytes, lowLatency: $lowLatency): ${e.message}", TAG)
            }
        }

        AppLogger.error("Failed to initialize any AudioTrack for channelMask: 0x${Integer.toHexString(channelMask)}", TAG)
        return -1
    }

    private fun createAudioTrack(sampleRate: Int, channelConfig: Int, bufferSize: Int, lowLatency: Boolean): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                if (lowLatency && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                }
            }
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)

            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            trackBuilder.build()
        } else {
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }
    }

    override fun start() {
        synchronized(lock) {
            try {
                if (audioTrack == null) {
                    initializeAudioTrack()
                }
                audioTrack?.play()
                isTrackRunning.set(true)
            } catch (e: Exception) {
                AppLogger.error("Failed to start AudioTrack: ${e.message}", TAG)
                isTrackRunning.set(false)
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            isTrackRunning.set(false)
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (e: Exception) {
                AppLogger.warn("Error stopping AudioTrack: ${e.message}", TAG)
            }
        }
    }

    override fun dispose() {
        synchronized(lock) {
            disposeInternal()
        }
    }

    fun cleanup() {
        dispose()
    }

    private fun disposeInternal() {
        isTrackRunning.set(false)
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (ignored: Exception) {}
            try {
                track.release()
            } catch (e: Exception) {
                AppLogger.warn("Error releasing AudioTrack: ${e.message}", TAG)
            }
        }
        audioTrack = null
    }

    override fun setMuted(muted: Boolean) {
        synchronized(lock) {
            this.isMuted = muted
            val vol = if (muted || (muteOnFocusLoss && !appFocused)) 0.0f else 1.0f
            audioTrack?.setVolume(vol)
        }
    }

    fun updateFocus(focused: Boolean) {
        synchronized(lock) {
            this.appFocused = focused
            val vol = if (isMuted || (muteOnFocusLoss && !focused)) 0.0f else 1.0f
            audioTrack?.setVolume(vol)
        }
    }

    fun configure(muteOnFocusLoss: Boolean, appFocused: Boolean) {
        synchronized(lock) {
            this.muteOnFocusLoss = muteOnFocusLoss
            this.appFocused = appFocused
            val vol = if (isMuted || (muteOnFocusLoss && !appFocused)) 0.0f else 1.0f
            audioTrack?.setVolume(vol)
        }
    }

    fun playSample(sampleData: ShortArray) {
        if (!isTrackRunning.get()) return

        val track = audioTrack ?: return

        // Backlog protection:
        // Bound latency at 40 ms by dropping sample if common-c's pending audio duration exceeds 40 ms
        val pendingDuration = MoonBridge.getPendingAudioDuration()
        if (pendingDuration < 40) {
            try {
                track.write(sampleData, 0, sampleData.size)
            } catch (e: Exception) {
                AppLogger.warn("AudioTrack write exception: ${e.message}", TAG)
            }
        } else {
            AppLogger.info("Audio backlog exceeded limit (${pendingDuration}ms), dropping frame to bound latency", TAG)
        }
    }
}
