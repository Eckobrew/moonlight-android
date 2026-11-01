package com.moonlight.material.stream.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.moonlight.material.core.models.VideoCodec
import com.moonlight.material.core.utils.AppLogger
import java.nio.ByteBuffer

interface VideoDecoderListener {
    fun onFrameDecoded(decodeTimeMs: Double)
    fun onFrameDropped()
    fun onDecoderError(error: String)
}

class MediaCodecDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val codec: VideoCodec,
    private val enableHdr: Boolean = false,
    private val listener: VideoDecoderListener? = null
) {
    private val TAG = "DECODER"
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false

    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    var currentFps: Double = 0.0
        private set

    fun start() {
        val mimeType = when (codec) {
            VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            VideoCodec.AV1 -> "video/av01"
            VideoCodec.AUTO -> MediaFormat.MIMETYPE_VIDEO_HEVC
        }

        try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
                if (enableHdr) {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                }
            }

            mediaCodec = MediaCodec.createDecoderByType(mimeType)
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()
            isRunning = true
            AppLogger.info("Started hardware decoder for $mimeType (${width}x${height} @ ${fps}fps)", TAG)
        } catch (e: Exception) {
            AppLogger.error("Failed to initialize MediaCodec for $mimeType: ${e.message}", TAG)
            listener?.onDecoderError(e.message ?: "Decoder init error")
        }
    }

    fun submitDecodeUnit(data: ByteArray, offset: Int, length: Int, presentationTimeUs: Long, isKeyFrame: Boolean) {
        if (!isRunning || mediaCodec == null) return

        val startTime = System.nanoTime()
        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000L) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(data, offset, length)
                    val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mediaCodec?.queueInputBuffer(inputBufferIndex, 0, length, presentationTimeUs, flags)
                }
            } else {
                listener?.onFrameDropped()
            }

            // Drain output buffers
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0L) ?: -1
            while (outputBufferIndex >= 0) {
                // Render directly to surface zero-copy!
                mediaCodec?.releaseOutputBuffer(outputBufferIndex, true)
                val decodeTimeMs = (System.nanoTime() - startTime) / 1_000_000.0
                listener?.onFrameDecoded(decodeTimeMs)

                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTimestamp >= 1000) {
                    currentFps = (frameCount * 1000.0) / (now - lastFpsTimestamp)
                    frameCount = 0
                    lastFpsTimestamp = now
                }

                outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0L) ?: -1
            }
        } catch (e: Exception) {
            AppLogger.warn("Error submitting decode unit: ${e.message}", TAG)
        }
    }

    fun stop() {
        isRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            AppLogger.warn("Error releasing MediaCodec: ${e.message}", TAG)
        } finally {
            mediaCodec = null
        }
    }
}
