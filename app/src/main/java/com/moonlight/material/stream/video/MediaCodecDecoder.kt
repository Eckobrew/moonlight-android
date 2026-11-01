package com.moonlight.material.stream.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.core.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

interface VideoDecoderListener {
    fun onFrameDecoded(decodeTimeMs: Double)
    fun onFrameDropped()
    fun onDecoderError(error: String)
    fun onFormatChanged(width: Int, height: Int) {}
}

/**
 * High-performance hardware video decoder using Android MediaCodec.
 *
 * Implements low-latency streaming pipeline for H.264, HEVC, and AV1 video streams.
 * Handles parameter sets (CSD) for H.264/HEVC and Sequence Header OBUs for AV1.
 * Decouples input submission from output rendering using a dedicated renderer thread,
 * and paces frame presentation via [FramePacer] and [StreamHDRState].
 */
class MediaCodecDecoder(
    private var surface: Surface,
    private var streamWidth: Int,
    private var streamHeight: Int,
    private var streamFps: Int,
    private val enablePacing: Boolean = false,
    private val listener: VideoDecoderListener? = null
) {
    private val TAG = "MediaCodecDecoder"

    private var videoDecoder: MediaCodec? = null
    private var rendererThread: Thread? = null
    private val isStopping = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    private val pacer = FramePacer(enabled = enablePacing, refreshRate = streamFps.toDouble())
    private val hdrState = StreamHDRState()

    private var videoFormat: Int = MoonBridge.VIDEO_FORMAT_H264
    private var mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private var configuredFormat: MediaFormat? = null

    // Parameter sets storage
    private val spsBuffers = mutableListOf<ByteArray>()
    private val ppsBuffers = mutableListOf<ByteArray>()
    private val vpsBuffers = mutableListOf<ByteArray>()
    private var av1SequenceHeader: ByteArray? = null
    private var submittedCsd = false

    // Timing and metrics
    private var baseTimestampUs: Long = 0L
    private var lastTimestampUs: Long = 0L
    private var lastFrameNumber: Int = 0

    var totalFramesReceived: Long = 0
        private set
    var totalFramesRendered: Long = 0
        private set
    var totalFramesDropped: Long = 0
        private set
    val currentFps: Double get() = performance.snapshot().renderedFps
    val averageDecodeTimeMs: Double get() = performance.snapshot().decodeTimeMs

    private val decoderLock = Any()
    val performance = VideoPerformanceTracker()

    fun setup(videoFormat: Int, width: Int, height: Int, redrawRate: Int): Int {
        synchronized(decoderLock) {
            this.videoFormat = videoFormat
            this.streamWidth = width
            this.streamHeight = height
            this.streamFps = redrawRate
            pacer.configure(enablePacing, redrawRate.toDouble())

            mimeType = when {
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> "video/av01"
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 -> MediaFormat.MIMETYPE_VIDEO_HEVC
                else -> MediaFormat.MIMETYPE_VIDEO_AVC
            }

            spsBuffers.clear()
            ppsBuffers.clear()
            vpsBuffers.clear()
            av1SequenceHeader = null
            submittedCsd = false
            baseTimestampUs = 0L
            lastTimestampUs = 0L
            lastFrameNumber = 0
            performance.reset()

            return initializeDecoder()
        }
    }

    private fun initializeDecoder(): Int {
        try {
            stopDecoderInternal()

            val format = MediaFormat.createVideoFormat(mimeType, streamWidth, streamHeight).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, streamWidth * streamHeight * 3 / 2)
                setInteger(MediaFormat.KEY_FRAME_RATE, streamFps)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority

                // Low latency mode on API 30+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }

                // Adaptive playback capabilities
                setInteger(MediaFormat.KEY_MAX_WIDTH, streamWidth)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, streamHeight)

                // Operating rate hint
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())

                // Apply color and HDR metadata
                hdrState.applyToMediaFormat(this)
            }

            AppLogger.info("Creating decoder for $mimeType (${streamWidth}x${streamHeight} @ ${streamFps}fps)", TAG)
            val codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(format, surface, null, 0)
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            codec.setOnFrameRenderedListener({ source, _, renderTimeNs ->
                if (source === videoDecoder && !isStopping.get()) performance.renderedFrame(renderTimeNs)
            }, Handler(Looper.getMainLooper()))
            codec.start()

            videoDecoder = codec
            configuredFormat = format
            isRunning.set(true)
            isStopping.set(false)

            startRendererThread()
            return MoonBridge.DR_OK
        } catch (e: Exception) {
            AppLogger.error("Failed to initialize MediaCodec for $mimeType: ${e.message}", TAG)
            listener?.onDecoderError(e.message ?: "Failed to initialize decoder")
            return -1
        }
    }

    fun start() {
        synchronized(decoderLock) {
            if (videoDecoder == null && isRunning.get()) {
                initializeDecoder()
            }
        }
    }

    fun stop() {
        synchronized(decoderLock) {
            isStopping.set(true)
            isRunning.set(false)
            stopDecoderInternal()
            pacer.stop()
        }
    }

    fun cleanup() {
        stop()
    }

    private fun stopDecoderInternal() {
        isStopping.set(true)
        rendererThread?.interrupt()
        try {
            rendererThread?.join(500)
        } catch (ignored: InterruptedException) {}
        rendererThread = null

        videoDecoder?.let { codec ->
            try {
                codec.stop()
            } catch (e: Exception) {
                AppLogger.warn("Error stopping MediaCodec: ${e.message}", TAG)
            }
            try {
                codec.release()
            } catch (e: Exception) {
                AppLogger.warn("Error releasing MediaCodec: ${e.message}", TAG)
            }
        }
        videoDecoder = null
    }

    fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        synchronized(decoderLock) {
            val changed = hdrState.update(enabled, hdrMetadata)
            if (changed && isRunning.get()) {
                AppLogger.info("HDR mode changed: enabled=$enabled, reconfiguring decoder", TAG)
                initializeDecoder()
            }
        }
    }

    fun setSurface(newSurface: Surface) {
        synchronized(decoderLock) {
            this.surface = newSurface
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && videoDecoder != null) {
                try {
                    videoDecoder?.setOutputSurface(newSurface)
                    return
                } catch (e: Exception) {
                    AppLogger.warn("setOutputSurface failed, recreating decoder: ${e.message}", TAG)
                }
            }
            if (isRunning.get()) {
                initializeDecoder()
            }
        }
    }

    fun submitDecodeUnit(
        frameData: ByteArray,
        length: Int,
        bufferType: Int,
        frameNumber: Int,
        frameType: Int,
        frameHostProcessingLatency: Char,
        receiveTimeUs: Long,
        enqueueTimeUs: Long
    ): Int {
        if (isStopping.get() || !isRunning.get()) {
            return MoonBridge.DR_OK
        }

        // Missing frame detection
        if (lastFrameNumber != 0 && frameNumber > lastFrameNumber + 1) {
            val lost = frameNumber - lastFrameNumber - 1
            totalFramesDropped += lost
            listener?.onFrameDropped()
        }

        // Reset CSD buffer accumulation on new IDR frame
        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            spsBuffers.clear()
            ppsBuffers.clear()
            vpsBuffers.clear()
        }

        lastFrameNumber = frameNumber

        // Handle CSD (SPS/PPS/VPS) for H.264 and HEVC
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            when (bufferType) {
                MoonBridge.BUFFER_TYPE_SPS -> {
                    spsBuffers.add(frameData.copyOf(length))
                    return MoonBridge.DR_OK
                }
                MoonBridge.BUFFER_TYPE_PPS -> {
                    ppsBuffers.add(frameData.copyOf(length))
                    return MoonBridge.DR_OK
                }
                MoonBridge.BUFFER_TYPE_VPS -> {
                    vpsBuffers.add(frameData.copyOf(length))
                    return MoonBridge.DR_OK
                }
            }
        }

        performance.receivedFrame(frameNumber)
        totalFramesReceived++
        val codec = videoDecoder ?: return MoonBridge.DR_NEED_IDR

        try {
            // Check if we need to submit pending CSD before first IDR picture data
            if (frameType == MoonBridge.FRAME_TYPE_IDR && (!submittedCsd || spsBuffers.isNotEmpty())) {
                submitParameterSets(codec)
            }

            // For AV1, discover sequence header if present
            if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 && frameType == MoonBridge.FRAME_TYPE_IDR) {
                val obus = AV1OBUParser.parse(frameData, 0, length)
                for (obu in obus) {
                    if (obu.type == AV1OBUParser.OBU_SEQUENCE_HEADER) {
                        av1SequenceHeader = obu.raw
                        submitAV1SequenceHeader(codec, obu.raw)
                        break
                    }
                }
            }

            // Dequeue input buffer with a short timeout to prevent blocking native thread
            val inputBufferIndex = codec.dequeueInputBuffer(15000L) // 15ms timeout
            if (inputBufferIndex < 0) {
                AppLogger.warn("Decoder input buffer starved, requesting IDR frame", TAG)
                totalFramesDropped++
                performance.droppedDecoderFrame()
                listener?.onFrameDropped()
                MoonBridge.requestIdrFrame()
                return MoonBridge.DR_NEED_IDR
            }

            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: run {
                MoonBridge.requestIdrFrame()
                return MoonBridge.DR_NEED_IDR
            }

            inputBuffer.clear()
            if (length > inputBuffer.remaining()) {
                AppLogger.error("Decode unit length ($length) exceeds input buffer (${inputBuffer.remaining()})", TAG)
                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                MoonBridge.requestIdrFrame()
                return MoonBridge.DR_NEED_IDR
            }

            inputBuffer.put(frameData, 0, length)

            // Normalize presentation timestamp to uptime microseconds
            if (baseTimestampUs == 0L) {
                baseTimestampUs = (SystemClock.uptimeMillis() * 1000L) - enqueueTimeUs
            }
            var ptsUs = baseTimestampUs + enqueueTimeUs
            if (ptsUs <= lastTimestampUs) {
                ptsUs = lastTimestampUs + 1
            }
            lastTimestampUs = ptsUs

            var flags = 0
            if (frameType == MoonBridge.FRAME_TYPE_IDR) {
                flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }

            performance.queuedFrame(ptsUs)
            codec.queueInputBuffer(inputBufferIndex, 0, length, ptsUs, flags)
            return MoonBridge.DR_OK
        } catch (e: Exception) {
            AppLogger.error("Exception in submitDecodeUnit: ${e.message}", TAG)
            MoonBridge.requestIdrFrame()
            return MoonBridge.DR_NEED_IDR
        }
    }

    private fun submitParameterSets(codec: MediaCodec) {
        if (spsBuffers.isEmpty() && ppsBuffers.isEmpty() && vpsBuffers.isEmpty()) return

        val inputIndex = codec.dequeueInputBuffer(10000L)
        if (inputIndex >= 0) {
            val buffer = codec.getInputBuffer(inputIndex) ?: return
            buffer.clear()
            for (vps in vpsBuffers) buffer.put(vps)
            for (sps in spsBuffers) buffer.put(sps)
            for (pps in ppsBuffers) buffer.put(pps)
            codec.queueInputBuffer(inputIndex, 0, buffer.position(), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            submittedCsd = true
        }
    }

    private fun submitAV1SequenceHeader(codec: MediaCodec, seqHeader: ByteArray) {
        val inputIndex = codec.dequeueInputBuffer(10000L)
        if (inputIndex >= 0) {
            val buffer = codec.getInputBuffer(inputIndex) ?: return
            buffer.clear()
            buffer.put(seqHeader)
            codec.queueInputBuffer(inputIndex, 0, seqHeader.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            submittedCsd = true
        }
    }

    private fun startRendererThread() {
        rendererThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()

            while (!isStopping.get()) {
                val codec = videoDecoder ?: break

                try {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 30000L) // 30ms timeout

                    if (outIndex >= 0) {
                        val isFrame = bufferInfo.flags and
                            (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                        if (isFrame) {
                            val decodeMs = performance.decodedFrame(bufferInfo.presentationTimeUs)
                            // Count outputs regardless of whether a matching input timestamp exists.
                            listener?.onFrameDecoded(decodeMs ?: 0.0)
                        }
                        val releaseTimestampNs = pacer.calculateReleaseTimeNs()
                        codec.releaseOutputBuffer(outIndex, releaseTimestampNs)
                        if (isFrame) totalFramesRendered++
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        val width = if (newFormat.containsKey("crop-right") && newFormat.containsKey("crop-left"))
                            newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1
                            else newFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val height = if (newFormat.containsKey("crop-bottom") && newFormat.containsKey("crop-top"))
                            newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1
                            else newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        AppLogger.info("MediaCodec output format changed: ${width}x${height}", TAG)
                        listener?.onFormatChanged(width, height)
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // Timeout: loop around
                    }
                } catch (e: IllegalStateException) {
                    if (!isStopping.get()) {
                        AppLogger.warn("MediaCodec state exception in render loop: ${e.message}", TAG)
                    }
                    break
                } catch (e: Exception) {
                    if (!isStopping.get()) {
                        AppLogger.warn("Exception in render loop: ${e.message}", TAG)
                    }
                    break
                }
            }
        }, "MediaCodecRendererThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }
}
