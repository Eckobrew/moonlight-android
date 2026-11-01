package com.moonlight.material.stream.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.moonlight.material.core.models.AudioConfigurationMode
import com.moonlight.material.core.utils.AppLogger

class AudioPlaybackEngine {
    private val TAG = "AUDIO"
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun start(mode: AudioConfigurationMode, muteOnFocusLoss: Boolean = false) {
        val sampleRate = 48000
        val channelConfig = when (mode) {
            AudioConfigurationMode.STEREO -> AudioFormat.CHANNEL_OUT_STEREO
            AudioConfigurationMode.SURROUND_51 -> AudioFormat.CHANNEL_OUT_5POINT1
            AudioConfigurationMode.SURROUND_71 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize * 2

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            AppLogger.info("Started AudioTrack for ${mode.displayName} (48000 Hz, buffer: ${bufferSize}b)", TAG)
        } catch (e: Exception) {
            AppLogger.error("Failed to initialize AudioTrack: ${e.message}", TAG)
        }
    }

    fun playSample(pcmData: ByteArray, offset: Int, length: Int) {
        if (!isPlaying || audioTrack == null) return
        try {
            audioTrack?.write(pcmData, offset, length, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            AppLogger.warn("AudioTrack write failed: ${e.message}", TAG)
        }
    }

    fun init(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int {
        val channelCount = (audioConfiguration shr 8) and 0xFF
        val channelMask = when (channelCount) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(samplesPerFrame * channelCount * 2)
        return try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            0
        } catch (e: Exception) {
            AppLogger.error("Failed to init AudioTrack: ${e.message}", TAG)
            -1
        }
    }

    fun playSample(sampleData: ShortArray) {
        if (!isPlaying || audioTrack == null) return
        try {
            audioTrack?.write(sampleData, 0, sampleData.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            AppLogger.warn("AudioTrack write failed: ${e.message}", TAG)
        }
    }

    fun cleanup() {
        stop()
    }

    fun stop() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            AppLogger.warn("Error releasing AudioTrack: ${e.message}", TAG)
        } finally {
            audioTrack = null
        }
    }
}
