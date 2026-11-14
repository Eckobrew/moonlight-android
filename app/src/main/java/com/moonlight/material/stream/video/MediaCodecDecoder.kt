package com.moonlight.material.stream.video

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.core.models.DecoderPreference
import com.moonlight.material.core.models.FramePacingMode
import com.moonlight.material.core.utils.AppLogger
import org.jcodec.codecs.h264.H264Utils
import org.jcodec.codecs.h264.io.model.SeqParameterSet
import org.jcodec.codecs.h264.io.model.VUIParameters
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface VideoDecoderListener {
    fun onFrameDecoded(decodeTimeMs: Double)
    fun onFrameDropped()
    fun onDecoderError(error: String)
    fun onFormatChanged(width: Int, height: Int) {}
}

/**
 * High-performance hardware video decoder using Android MediaCodec, aligned with
 * upstream moonlight-android's MediaCodecDecoderRenderer:
 * - Multi-step vendor low-latency MediaFormat configuration via [MediaCodecHelper]
 * - H.264 SPS bitstream patching (`num_ref_frames = 1`, VUI `max_dec_frame_buffering`, `num_reorder_frames = 0`)
 * - Fused IDR CSD submission and pre-fetched input buffers
 * - Output buffer queue draining for minimum latency and Choreographer VSync pacing
 * - Coordinated multi-thread CodecException recovery (Flush / Restart / Reset)
 */
class MediaCodecDecoder(
    private var surface: Surface,
    private var streamWidth: Int,
    private var streamHeight: Int,
    private var streamFps: Int,
    private val enablePacing: Boolean = false,
    private val enableVsync: Boolean = true,
    private val framePacingMode: FramePacingMode = if (enablePacing) FramePacingMode.BALANCED else FramePacingMode.MIN_LATENCY,
    private val appVsyncOffsetNanos: Long = 0L,
    private val fullRangeColor: Boolean = false,
    private val colorSpace: Int = MoonBridge.COLORSPACE_REC_709,
    private val refFrameInvalidationAvc: Boolean = false,
    private val refFrameInvalidationHevc: Boolean = false,
    private val refFrameInvalidationAv1: Boolean = false,
    private val decoderPreference: DecoderPreference = DecoderPreference.AUTOMATIC,
    private val onCrashDetected: (() -> Unit)? = null,
    private val listener: VideoDecoderListener? = null
) : Choreographer.FrameCallback {
    private val TAG = "MediaCodecDecoder"

    private var videoDecoder: MediaCodec? = null
    private var activeDecoderInfo: MediaCodecInfo? = null
    private var configuredFormat: MediaFormat? = null
    private var rendererThread: Thread? = null
    private var choreographerHandlerThread: HandlerThread? = null
    private var choreographerHandler: Handler? = null
    private var frameRenderedHandlerThread: HandlerThread? = null
    private var frameRenderedHandler: Handler? = null

    private val isStopping = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    private val pacer = FramePacer(enabled = enablePacing, refreshRate = streamFps.toDouble())
    private val hdrState = StreamHDRState()

    private var videoFormat: Int = MoonBridge.VIDEO_FORMAT_H264
    private var mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC

    // Parameter sets storage
    private val vpsBuffers = mutableListOf<ByteArray>()
    private val spsBuffers = mutableListOf<ByteArray>()
    private val ppsBuffers = mutableListOf<ByteArray>()
    private var submittedCsd = false
    private var adaptivePlayback = false
    private var fusedIdrFrame = false
    private var refFrameInvalidationActive = false
    private var constrainedHighProfile = false
    private var needsSpsBitstreamFixup = false
    private var needsBaselineSpsHack = false
    private var isExynos4 = false
    private var replaySps: ByteArray? = null

    // Pre-fetched input buffer state
    private var nextInputBufferIndex: Int = -1
    private var nextInputBuffer: ByteBuffer? = null

    // Choreographer balanced pacing queue
    private val outputBufferQueue = LinkedBlockingQueue<Int>()
    private var lastRenderedFrameTimeNanos: Long = 0L

    // Codec recovery state machine
    private val codecRecoveryType = AtomicInteger(CR_RECOVERY_TYPE_NONE)
    private val codecRecoveryLock = Object()
    private var codecRecoveryThreadQuiescedFlags = 0
    private var codecRecoveryAttempts = 0

    // Timing and metrics
    private var baseTimestampUs: Long = 0L
    private var lastTimestampUs: Long = 0L
    private var lastFrameNumber: Int = 0
    private var numVpsIn: Int = 0
    private var numSpsIn: Int = 0
    private var numPpsIn: Int = 0

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

    companion object {
        private const val CR_MAX_TRIES = 10
        private const val CR_RECOVERY_TYPE_NONE = 0
        private const val CR_RECOVERY_TYPE_FLUSH = 1
        private const val CR_RECOVERY_TYPE_RESTART = 2
        private const val CR_RECOVERY_TYPE_RESET = 3

        private const val CR_FLAG_INPUT_THREAD = 0x1
        private const val CR_FLAG_RENDER_THREAD = 0x2
        private const val CR_FLAG_CHOREOGRAPHER = 0x4
        private const val CR_FLAG_ALL = CR_FLAG_INPUT_THREAD or CR_FLAG_RENDER_THREAD or CR_FLAG_CHOREOGRAPHER

        private const val OUTPUT_BUFFER_QUEUE_LIMIT = 2
    }

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

            refFrameInvalidationActive = when {
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> refFrameInvalidationAv1
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 -> refFrameInvalidationHevc
                else -> refFrameInvalidationAvc
            }

            vpsBuffers.clear()
            spsBuffers.clear()
            ppsBuffers.clear()
            submittedCsd = false
            nextInputBufferIndex = -1
            nextInputBuffer = null
            outputBufferQueue.clear()
            baseTimestampUs = 0L
            lastTimestampUs = 0L
            lastFrameNumber = 0
            numVpsIn = 0
            numSpsIn = 0
            numPpsIn = 0
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
            codecRecoveryAttempts = 0
            performance.reset()

            return if (initializeDecoder(false)) MoonBridge.DR_OK else -1
        }
    }

    private fun selectCandidates(): List<MediaCodecInfo> {
        val allDecoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            if (info.isEncoder || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias)) {
                return@filter false
            }
            val supportsMime = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            if (!supportsMime) return@filter false

            val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly || !info.isHardwareAccelerated
            } else {
                info.name.startsWith("OMX.google.", ignoreCase = true) ||
                    info.name.contains("sw", ignoreCase = true)
            }
            when (decoderPreference) {
                DecoderPreference.HARDWARE -> !software
                DecoderPreference.SOFTWARE -> software
                DecoderPreference.AUTOMATIC -> true
            }
        }

        if (decoderPreference == DecoderPreference.AUTOMATIC) {
            val preferred = MediaCodecHelper.findProbableSafeDecoder(mimeType, -1)
            if (preferred != null) {
                val others = allDecoders.filterNot { it.name.equals(preferred.name, ignoreCase = true) }
                return listOf(preferred) + others
            }
        }

        return allDecoders.sortedWith(
            compareBy<MediaCodecInfo> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.isSoftwareOnly else it.name.startsWith("OMX.google.", true)
            }.thenByDescending {
                MediaCodecHelper.decoderSupportsAndroidRLowLatency(it, mimeType)
            }
        )
    }

    private fun createBaseMediaFormat(decoderInfo: MediaCodecInfo): MediaFormat {
        val format = MediaFormat.createVideoFormat(mimeType, streamWidth, streamHeight)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_FRAME_RATE, streamFps)
        }
        if (adaptivePlayback) {
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, streamWidth)
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, streamHeight)
        }
        hdrState.applyToMediaFormat(
            format = format,
            videoFormat = videoFormat,
            fullRange = fullRangeColor,
            colorSpace = colorSpace
        )
        return format
    }

    private fun configureAndStartDecoder(codec: MediaCodec, format: MediaFormat): Boolean {
        try {
            codec.configure(format, surface, null, 0)
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            val handler = ensureFrameRenderedHandler()
            codec.setOnFrameRenderedListener({ source, _, renderTimeNs ->
                if (source === videoDecoder && !isStopping.get()) {
                    performance.renderedFrame(renderTimeNs)
                }
            }, handler)
            codec.start()
            AppLogger.info("Using codec ${codec.name} for $mimeType ($format)", TAG)
            return true
        } catch (e: Exception) {
            AppLogger.warn("Failed to configure/start ${codec.name}: ${e.message}", TAG)
            return false
        }
    }

    private fun ensureFrameRenderedHandler(): Handler {
        var handler = frameRenderedHandler
        if (handler == null) {
            val thread = HandlerThread("Video - FrameRendered", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
            }
            frameRenderedHandlerThread = thread
            handler = Handler(thread.looper)
            frameRenderedHandler = handler
        }
        return handler
    }

    private fun initializeDecoder(shortCircuit: Boolean): Boolean {
        stopDecoderInternal()

        val candidates = runCatching { selectCandidates() }.getOrElse {
            AppLogger.warn("Could not enumerate video decoders: ${it.message}", TAG)
            emptyList()
        }

        var failure = "No decoder could start for $mimeType"
        for (decoderInfo in candidates) {
            adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(decoderInfo, mimeType)
            fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(decoderInfo, mimeType)
            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(decoderInfo.name)
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(decoderInfo.name)
            isExynos4 = MediaCodecHelper.isExynos4Device()

            for (tryNumber in 0..6) {
                var codecToRelease: MediaCodec? = null
                try {
                    val codec = MediaCodec.createByCodecName(decoderInfo.name)
                    codecToRelease = codec

                    val format = createBaseMediaFormat(decoderInfo)
                    val newOptions = MediaCodecHelper.setDecoderLowLatencyOptions(
                        format,
                        decoderInfo,
                        mimeType,
                        tryNumber
                    )

                    if (configureAndStartDecoder(codec, format)) {
                        videoDecoder = codec
                        activeDecoderInfo = decoderInfo
                        configuredFormat = format
                        submittedCsd = false
                        nextInputBufferIndex = -1
                        nextInputBuffer = null
                        outputBufferQueue.clear()
                        isRunning.set(true)
                        isStopping.set(false)
                        startChoreographerIfNeeded()
                        startRendererThread()
                        return true
                    } else {
                        codec.release()
                        codecToRelease = null
                    }

                    if (shortCircuit || !newOptions) {
                        break
                    }
                } catch (e: Exception) {
                    runCatching { codecToRelease?.release() }
                    failure = e.message ?: "Failed to initialize decoder"
                    AppLogger.warn("Decoder try $tryNumber failed on ${decoderInfo.name}: $failure", TAG)
                    if (shortCircuit) break
                }
            }
        }

        listener?.onDecoderError(failure)
        return false
    }

    fun start() {
        synchronized(decoderLock) {
            if (videoDecoder == null && isRunning.get()) {
                initializeDecoder(false)
            }
        }
    }

    fun stop() {
        synchronized(decoderLock) {
            isStopping.set(true)
            isRunning.set(false)
            synchronized(codecRecoveryLock) {
                codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                codecRecoveryLock.notifyAll()
            }
            stopDecoderInternal()
            pacer.stop()
        }
    }

    fun cleanup() {
        stop()
    }

    private fun stopDecoderInternal() {
        isStopping.set(true)

        choreographerHandlerThread?.let { thread ->
            choreographerHandler?.post {
                Choreographer.getInstance().removeFrameCallback(this)
            }
            thread.quitSafely()
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
            }
        }
        choreographerHandlerThread = null
        choreographerHandler = null

        rendererThread?.interrupt()
        try {
            rendererThread?.join(500)
        } catch (_: InterruptedException) {
        }
        rendererThread = null

        frameRenderedHandlerThread?.let { thread ->
            thread.quitSafely()
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
            }
        }
        frameRenderedHandlerThread = null
        frameRenderedHandler = null

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
        nextInputBufferIndex = -1
        nextInputBuffer = null
        outputBufferQueue.clear()
    }

    private fun startChoreographerIfNeeded() {
        if (framePacingMode == FramePacingMode.BALANCED && enableVsync && choreographerHandlerThread == null) {
            val thread = HandlerThread(
                "Video - Choreographer",
                Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_MORE_FAVORABLE
            ).apply { start() }
            choreographerHandlerThread = thread
            val handler = Handler(thread.looper)
            choreographerHandler = handler
            handler.post {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (isStopping.get()) return

        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)

        val actualFrameTimeNanos = frameTimeNanos - appVsyncOffsetNanos
        // Allow frame within 80% of frame period to avoid microstutter on VSync jitter
        val frameIntervalNs = 800_000_000L / streamFps.coerceAtLeast(1)
        if (actualFrameTimeNanos - lastRenderedFrameTimeNanos >= frameIntervalNs) {
            val nextOutputBuffer = outputBufferQueue.poll()
            if (nextOutputBuffer != null) {
                try {
                    videoDecoder?.releaseOutputBuffer(nextOutputBuffer, actualFrameTimeNanos)
                    lastRenderedFrameTimeNanos = actualFrameTimeNanos
                    totalFramesRendered++
                } catch (_: IllegalStateException) {
                    try {
                        synchronized(codecRecoveryLock) {
                            if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
                                codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (!isStopping.get()) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        val changed = hdrState.update(enabled, hdrMetadata)
        if (changed && isRunning.get()) {
            AppLogger.info("HDR mode changed: enabled=$enabled, scheduling codec restart", TAG)
            synchronized(codecRecoveryLock) {
                activeDecoderInfo?.let { info ->
                    configuredFormat = createBaseMediaFormat(info)
                    MediaCodecHelper.setDecoderLowLatencyOptions(configuredFormat!!, info, mimeType, 0)
                }
                if (codecRecoveryType.get() < CR_RECOVERY_TYPE_RESTART) {
                    codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
                }
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
                    AppLogger.warn("setOutputSurface failed, scheduling restart: ${e.message}", TAG)
                }
            }
            if (isRunning.get()) {
                synchronized(codecRecoveryLock) {
                    codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
                }
            }
        }
    }

    private fun quiesceAllThreads(): Int {
        var requiredFlags = CR_FLAG_INPUT_THREAD or CR_FLAG_RENDER_THREAD
        if (choreographerHandlerThread != null) {
            requiredFlags = requiredFlags or CR_FLAG_CHOREOGRAPHER
        }
        return requiredFlags
    }

    private fun doCodecRecoveryIfRequired(quiescenceFlag: Int) {
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE || isStopping.get()) {
            return
        }

        synchronized(codecRecoveryLock) {
            if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE || isStopping.get()) {
                return
            }

            codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or quiescenceFlag
            val requiredFlags = quiesceAllThreads()

            if (codecRecoveryThreadQuiescedFlags == requiredFlags) {
                nextInputBuffer = null
                nextInputBufferIndex = -1
                outputBufferQueue.clear()

                val recoveryAction = codecRecoveryType.get()
                codecRecoveryAttempts++
                AppLogger.info("Performing codec recovery (type=$recoveryAction, attempt=$codecRecoveryAttempts)", TAG)

                val codec = videoDecoder
                val format = configuredFormat

                if (codec != null && format != null) {
                    if (recoveryAction == CR_RECOVERY_TYPE_FLUSH) {
                        try {
                            codec.flush()
                            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                        } catch (e: Exception) {
                            AppLogger.warn("Codec flush failed, promoting to restart: ${e.message}", TAG)
                            codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
                        }
                    }

                    if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                        try {
                            codec.stop()
                            if (configureAndStartDecoder(codec, format)) {
                                submittedCsd = false
                                codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                            } else {
                                codecRecoveryType.set(CR_RECOVERY_TYPE_RESET)
                            }
                        } catch (e: Exception) {
                            AppLogger.warn("Codec restart failed, promoting to reset: ${e.message}", TAG)
                            codecRecoveryType.set(CR_RECOVERY_TYPE_RESET)
                        }
                    }

                    if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                        try {
                            codec.reset()
                            if (configureAndStartDecoder(codec, format)) {
                                submittedCsd = false
                                codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                            }
                        } catch (e: Exception) {
                            AppLogger.warn("Codec reset failed: ${e.message}", TAG)
                        }
                    }
                }

                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    if (codecRecoveryAttempts <= CR_MAX_TRIES) {
                        // Request IDR after recovery
                        MoonBridge.requestIdrFrame()
                    } else {
                        onCrashDetected?.invoke()
                        listener?.onDecoderError("Video decoder recovery failed after $CR_MAX_TRIES attempts")
                    }
                    codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                }

                codecRecoveryThreadQuiescedFlags = 0
                codecRecoveryLock.notifyAll()
            } else {
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE && !isStopping.get()) {
                    try {
                        codecRecoveryLock.wait(200)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun handleDecoderException(e: Exception): Boolean {
        if (isStopping.get()) return false

        if (e is MediaCodec.CodecException) {
            if (e.isTransient) {
                AppLogger.warn("Transient CodecException: ${e.diagnosticInfo}", TAG)
                return true
            }
            AppLogger.warn("CodecException (recoverable=${e.isRecoverable}): ${e.diagnosticInfo}", TAG)
            synchronized(codecRecoveryLock) {
                if (codecRecoveryAttempts < CR_MAX_TRIES) {
                    val target = if (e.isRecoverable) CR_RECOVERY_TYPE_RESTART else CR_RECOVERY_TYPE_RESET
                    if (codecRecoveryType.get() < target) {
                        codecRecoveryType.set(target)
                    }
                    return false
                }
            }
        } else if (e is IllegalStateException) {
            AppLogger.warn("IllegalStateException in MediaCodec: ${e.message}", TAG)
            synchronized(codecRecoveryLock) {
                if (codecRecoveryAttempts < CR_MAX_TRIES) {
                    if (codecRecoveryType.get() < CR_RECOVERY_TYPE_RESET) {
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET)
                    }
                    return false
                }
            }
        }

        return false
    }

    private fun patchH264Sps(rawSpsBuffer: ByteArray, length: Int): ByteArray {
        return try {
            // Skip 4-byte annex-B start code + 1-byte NALU header (offset 5)
            val spsData = ByteBuffer.wrap(rawSpsBuffer, 5, length - 5)
            val sps: SeqParameterSet = H264Utils.readSPS(spsData)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !refFrameInvalidationActive) {
                sps.levelIdc = when {
                    streamWidth <= 1280 && streamHeight <= 720 && streamFps <= 60 -> 31
                    streamWidth <= 1920 && streamHeight <= 1080 && streamFps <= 60 -> 42
                    else -> sps.levelIdc
                }
            }

            if (!refFrameInvalidationActive) {
                sps.numRefFrames = 1
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                constrainedHighProfile = (sps.profileIdc == 100)
                sps.constraintSet4Flag = sps.profileIdc == 100
                sps.constraintSet5Flag = sps.profileIdc == 100
            } else {
                constrainedHighProfile = false
                sps.constraintSet4Flag = false
                sps.constraintSet5Flag = false
            }

            var vuiParams = sps.vuiParams
            if (vuiParams == null) {
                vuiParams = VUIParameters()
                sps.vuiParams = vuiParams
            }
            var bitstreamRestriction = vuiParams.bitstreamRestriction
            if (bitstreamRestriction == null) {
                bitstreamRestriction = VUIParameters.BitstreamRestriction()
                vuiParams.bitstreamRestriction = bitstreamRestriction
            }

            bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true
            bitstreamRestriction.maxBytesPerPicDenom = 2
            bitstreamRestriction.maxBitsPerMbDenom = 1
            bitstreamRestriction.log2MaxMvLengthHorizontal = 16
            bitstreamRestriction.log2MaxMvLengthVertical = 16
            bitstreamRestriction.numReorderFrames = 0
            bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames

            val escapedNalu = H264Utils.writeSPS(sps, length)
            val outBytes = ByteArray(5 + escapedNalu.limit())
            System.arraycopy(rawSpsBuffer, 0, outBytes, 0, 5)
            escapedNalu.get(outBytes, 5, escapedNalu.limit())
            outBytes
        } catch (t: Throwable) {
            AppLogger.warn("Could not patch H.264 SPS, using unmodified SPS: ${t.message}", TAG)
            rawSpsBuffer.copyOf(length)
        }
    }

    private fun dequeueInputBufferIndex(codec: MediaCodec): Int {
        if (nextInputBuffer != null && nextInputBufferIndex >= 0) {
            val idx = nextInputBufferIndex
            nextInputBufferIndex = -1
            nextInputBuffer = null
            return idx
        }

        val startTime = SystemClock.uptimeMillis()
        while (!isStopping.get()) {
            doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
            try {
                val index = codec.dequeueInputBuffer(10000L)
                if (index >= 0) {
                    return index
                }
                if (SystemClock.uptimeMillis() - startTime >= 100L) {
                    return -1
                }
            } catch (e: Exception) {
                handleDecoderException(e)
                doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
                return -1
            }
        }
        return -1
    }

    private fun prefetchNextInputBuffer(codec: MediaCodec) {
        if (nextInputBuffer != null || isStopping.get() || codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
            return
        }
        try {
            val idx = codec.dequeueInputBuffer(0L)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)
                if (buf != null) {
                    buf.clear()
                    nextInputBufferIndex = idx
                    nextInputBuffer = buf
                } else {
                    codec.queueInputBuffer(idx, 0, 0, 0, 0)
                }
            }
        } catch (_: Exception) {
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

        doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)

        // Missing frame detection
        if (lastFrameNumber != 0 && frameNumber > lastFrameNumber + 1) {
            val lost = frameNumber - lastFrameNumber - 1
            totalFramesDropped += lost
            listener?.onFrameDropped()
        }

        // Reset CSD buffer accumulation on new IDR frame
        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            vpsBuffers.clear()
            spsBuffers.clear()
            ppsBuffers.clear()
        }

        lastFrameNumber = frameNumber

        // Handle CSD (VPS/SPS/PPS) for H.264 and HEVC
        if (bufferType != MoonBridge.BUFFER_TYPE_PICDATA) {
            when (bufferType) {
                MoonBridge.BUFFER_TYPE_VPS -> {
                    numVpsIn++
                    vpsBuffers.add(frameData.copyOf(length))
                    return MoonBridge.DR_OK
                }
                MoonBridge.BUFFER_TYPE_SPS -> {
                    numSpsIn++
                    val patched = if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                        patchH264Sps(frameData, length)
                    } else {
                        frameData.copyOf(length)
                    }
                    spsBuffers.add(patched)
                    return MoonBridge.DR_OK
                }
                MoonBridge.BUFFER_TYPE_PPS -> {
                    numPpsIn++
                    ppsBuffers.add(frameData.copyOf(length))
                    return MoonBridge.DR_OK
                }
            }
        }

        performance.receivedFrame(frameNumber, frameHostProcessingLatency.code.toShort())
        totalFramesReceived++
        val codec = videoDecoder ?: return MoonBridge.DR_NEED_IDR

        try {
            val hasPendingCsd = vpsBuffers.isNotEmpty() || spsBuffers.isNotEmpty() || ppsBuffers.isNotEmpty()
            val canFuseCsd = fusedIdrFrame && submittedCsd && frameType == MoonBridge.FRAME_TYPE_IDR

            if (hasPendingCsd && !canFuseCsd) {
                if (!submitSeparateParameterSets(codec)) {
                    return MoonBridge.DR_NEED_IDR
                }
            }

            val inputBufferIndex = dequeueInputBufferIndex(codec)
            if (inputBufferIndex < 0) {
                AppLogger.warn("Decoder input buffer unavailable, requesting IDR/RFI", TAG)
                totalFramesDropped++
                performance.droppedDecoderFrame()
                listener?.onFrameDropped()
                return MoonBridge.DR_NEED_IDR
            }

            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return MoonBridge.DR_NEED_IDR
            inputBuffer.clear()

            var totalBytes = length
            if (hasPendingCsd && canFuseCsd) {
                for (vps in vpsBuffers) totalBytes += vps.size
                for (sps in spsBuffers) totalBytes += sps.size
                for (pps in ppsBuffers) totalBytes += pps.size
            }

            if (totalBytes > inputBuffer.remaining()) {
                AppLogger.error("Decode unit length ($totalBytes) exceeds input buffer (${inputBuffer.remaining()})", TAG)
                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                return MoonBridge.DR_NEED_IDR
            }

            if (hasPendingCsd && canFuseCsd) {
                for (vps in vpsBuffers) inputBuffer.put(vps)
                for (sps in spsBuffers) inputBuffer.put(sps)
                for (pps in ppsBuffers) inputBuffer.put(pps)
                vpsBuffers.clear()
                spsBuffers.clear()
                ppsBuffers.clear()
            }

            inputBuffer.put(frameData, 0, length)

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
            codec.queueInputBuffer(inputBufferIndex, 0, totalBytes, ptsUs, flags)

            prefetchNextInputBuffer(codec)
            return MoonBridge.DR_OK
        } catch (e: Exception) {
            if (handleDecoderException(e)) {
                return MoonBridge.DR_NEED_IDR
            }
            doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
            return MoonBridge.DR_NEED_IDR
        }
    }

    private fun submitSeparateParameterSets(codec: MediaCodec): Boolean {
        val inputIndex = dequeueInputBufferIndex(codec)
        if (inputIndex < 0) return false
        val buffer = codec.getInputBuffer(inputIndex) ?: return false
        buffer.clear()
        for (vps in vpsBuffers) buffer.put(vps)
        for (sps in spsBuffers) buffer.put(sps)
        for (pps in ppsBuffers) buffer.put(pps)
        codec.queueInputBuffer(inputIndex, 0, buffer.position(), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        vpsBuffers.clear()
        spsBuffers.clear()
        ppsBuffers.clear()
        submittedCsd = true
        return true
    }

    @SuppressLint("NewApi")
    private fun startRendererThread() {
        rendererThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            val bufferInfo = MediaCodec.BufferInfo()

            while (!isStopping.get()) {
                doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD)
                val codec = videoDecoder ?: break

                try {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 50000L) // 50ms timeout

                    if (outIndex >= 0) {
                        var lastIndex = outIndex
                        var lastPtsUs = bufferInfo.presentationTimeUs
                        var lastFlags = bufferInfo.flags

                        if (framePacingMode == FramePacingMode.BALANCED && enableVsync && choreographerHandlerThread != null) {
                            // Balanced Choreographer mode: queue up to OUTPUT_BUFFER_QUEUE_LIMIT
                            val isFrame = (lastFlags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM)) == 0
                            if (isFrame) {
                                val decodeMs = performance.decodedFrame(lastPtsUs)
                                listener?.onFrameDecoded(decodeMs ?: 0.0)
                                if (outputBufferQueue.size >= OUTPUT_BUFFER_QUEUE_LIMIT) {
                                    val droppedIdx = outputBufferQueue.poll()
                                    if (droppedIdx != null) {
                                        codec.releaseOutputBuffer(droppedIdx, false)
                                    }
                                }
                                outputBufferQueue.add(lastIndex)
                            } else {
                                codec.releaseOutputBuffer(lastIndex, false)
                            }
                        } else {
                            // MIN_LATENCY, CAP_FPS, MAX_SMOOTHNESS, or VSync disabled:
                            // Drain any newer output buffers that are already ready so only the newest frame renders.
                            while (!isStopping.get()) {
                                val nextIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                                if (nextIndex < 0) break

                                // Drop the superseded older frame without rendering
                                codec.releaseOutputBuffer(lastIndex, false)
                                val droppedDecodeMs = performance.decodedFrame(lastPtsUs)
                                listener?.onFrameDecoded(droppedDecodeMs ?: 0.0)

                                lastIndex = nextIndex
                                lastPtsUs = bufferInfo.presentationTimeUs
                                lastFlags = bufferInfo.flags
                            }

                            val isFrame = (lastFlags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM)) == 0
                            if (isFrame) {
                                val decodeMs = performance.decodedFrame(lastPtsUs)
                                listener?.onFrameDecoded(decodeMs ?: 0.0)
                                when (framePacingMode) {
                                    FramePacingMode.CAP_FPS, FramePacingMode.MAX_SMOOTHNESS -> {
                                        // Timestamp 0 tells SurfaceFlinger to never drop late frames
                                        codec.releaseOutputBuffer(lastIndex, 0L)
                                    }
                                    FramePacingMode.BALANCED -> {
                                        // Balanced without Choreographer: use paced release timestamp
                                        codec.releaseOutputBuffer(lastIndex, pacer.calculateReleaseTimeNs())
                                    }
                                    FramePacingMode.MIN_LATENCY -> {
                                        // Minimum latency mode: render immediately with current nanoTime
                                        codec.releaseOutputBuffer(lastIndex, System.nanoTime())
                                    }
                                }
                                totalFramesRendered++
                            } else {
                                codec.releaseOutputBuffer(lastIndex, false)
                            }
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        val width = if (newFormat.containsKey("crop-right") && newFormat.containsKey("crop-left")) {
                            newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1
                        } else {
                            newFormat.getInteger(MediaFormat.KEY_WIDTH)
                        }
                        val height = if (newFormat.containsKey("crop-bottom") && newFormat.containsKey("crop-top")) {
                            newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1
                        } else {
                            newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                        AppLogger.info("MediaCodec output format changed: ${width}x${height}", TAG)
                        listener?.onFormatChanged(width, height)
                    }
                } catch (e: Exception) {
                    if (!isStopping.get()) {
                        handleDecoderException(e)
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD)
                    }
                }
            }
        }, "Video - Renderer").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }
}
