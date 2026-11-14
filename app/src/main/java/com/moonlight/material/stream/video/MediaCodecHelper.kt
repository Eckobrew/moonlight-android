package com.moonlight.material.stream.video

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.moonlight.material.core.utils.AppLogger
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Collections
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Pattern

/**
 * Helper for enumerating, filtering, and configuring Android hardware video decoders
 * with low-latency vendor parameters and Reference Frame Invalidation (RFI) heuristics.
 *
 * Ported from upstream com.limelight.binding.video.MediaCodecHelper.
 */
object MediaCodecHelper {

    private val preferredDecoders = LinkedList<String>()
    private val blacklistedDecoderPrefixes = LinkedList<String>()
    private val spsFixupBitstreamFixupDecoderPrefixes = LinkedList<String>()
    private val whitelistedHevcDecoders = LinkedList<String>()
    private val baselineProfileHackPrefixes = LinkedList<String>()
    private val directSubmitPrefixes = LinkedList<String>()
    private val refFrameInvalidationAvcPrefixes = LinkedList<String>()
    private val refFrameInvalidationHevcPrefixes = LinkedList<String>()
    private val useThreeBuffersPrefixes = LinkedList<String>()
    private val zeroCopyYpCbCr420Prefixes = LinkedList<String>()
    private val blacklistedAdaptivePlaybackPrefixes = LinkedList<String>()
    private val knownVendorLowLatencyOptions = LinkedList<String>()

    private var isLowEndSnapdragon = false
    private var isAdreno620 = false
    private var initialized = false

    init {
        directSubmitPrefixes.add("omx.qcom")
        directSubmitPrefixes.add("omx.sec")
        directSubmitPrefixes.add("omx.exynos")
        directSubmitPrefixes.add("omx.intel")
        directSubmitPrefixes.add("omx.brcm")
        directSubmitPrefixes.add("omx.TI")
        directSubmitPrefixes.add("omx.arc")
        directSubmitPrefixes.add("omx.nvidia")
        // All Codec2 decoders
        directSubmitPrefixes.add("c2.")

        refFrameInvalidationAvcPrefixes.add("omx.nvidia")
        refFrameInvalidationAvcPrefixes.add("c2.nvidia")
        refFrameInvalidationAvcPrefixes.add("omx.qcom")
        refFrameInvalidationAvcPrefixes.add("c2.qti")

        refFrameInvalidationHevcPrefixes.add("omx.exynos")
        refFrameInvalidationHevcPrefixes.add("c2.exynos")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Build.DEVICE.equals("dragon", ignoreCase = true)) {
            refFrameInvalidationHevcPrefixes.add("omx.nvidia")
            refFrameInvalidationHevcPrefixes.add("c2.nvidia")
        }

        // MediaTek decoders crash at 1080p HEVC when adaptive playback is enabled
        blacklistedAdaptivePlaybackPrefixes.add("omx.mtk")
        blacklistedAdaptivePlaybackPrefixes.add("c2.mtk")
        // Intel decoders have huge latency spikes when adaptive playback is enabled
        blacklistedAdaptivePlaybackPrefixes.add("omx.intel")
        blacklistedAdaptivePlaybackPrefixes.add("c2.intel")

        // Never use explicit software decoders unless nothing else exists
        blacklistedDecoderPrefixes.add("omx.google")
        blacklistedDecoderPrefixes.add("AVCDecoder")
        blacklistedDecoderPrefixes.add("omx.ffmpeg")
        if (Build.HARDWARE != "ranchu" && Build.HARDWARE != "cheets") {
            blacklistedDecoderPrefixes.add("c2.android")
        }

        // Force these prefixes first because they may appear after broken or high-latency decoders
        preferredDecoders.add("omx.Nvidia")
        preferredDecoders.add("omx.qcom")
        preferredDecoders.add("omx.sec")

        spsFixupBitstreamFixupDecoderPrefixes.add("omx.nvidia")
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.qcom")
        spsFixupBitstreamFixupDecoderPrefixes.add("omx.brcm")

        baselineProfileHackPrefixes.add("omx.intel")

        whitelistedHevcDecoders.add("omx.exynos")
        whitelistedHevcDecoders.add("omx.qcom")
        whitelistedHevcDecoders.add("c2.qti")
        whitelistedHevcDecoders.add("omx.rk")
        if (Build.DEVICE.equals("shieldtablet", ignoreCase = true)) {
            whitelistedHevcDecoders.add("omx.nvidia")
        }

        knownVendorLowLatencyOptions.add("vendor.qti-ext-dec-low-latency.enable")
        knownVendorLowLatencyOptions.add("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req")
        knownVendorLowLatencyOptions.add("vendor.rtc-ext-dec-low-latency.enable")
        knownVendorLowLatencyOptions.add("vendor.low-latency.enable")
    }

    @Synchronized
    fun initialize(context: Context, glRenderer: String = "") {
        if (initialized) return

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val configInfo = activityManager?.deviceConfigurationInfo
        val glesVersion = configInfo?.reqGlEsVersion ?: 0

        if (glRenderer.isNotEmpty()) {
            AppLogger.info("OpenGL ES Renderer: $glRenderer", "Video")
            if (glRenderer.lowercase(Locale.ROOT).contains("adreno")) {
                val matcher = Pattern.compile("(.*)([0-9]{3})(.*)").matcher(glRenderer)
                if (matcher.matches()) {
                    try {
                        val modelNumber = matcher.group(2)?.toInt() ?: 0
                        if (modelNumber == 620) {
                            isAdreno620 = true
                        }
                        if (modelNumber % 100 < 10) {
                            isLowEndSnapdragon = true
                        }
                    } catch (_: NumberFormatException) {
                    }
                }
            }
        }

        if (glesVersion != 0) {
            if (glesVersion >= 0x30000) {
                whitelistedHevcDecoders.add("omx.amlogic")
                whitelistedHevcDecoders.add("c2.amlogic")
                whitelistedHevcDecoders.add("omx.mtk")
                whitelistedHevcDecoders.add("c2.mtk")
                whitelistedHevcDecoders.add("omx.nvidia")
                whitelistedHevcDecoders.add("c2.nvidia")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    refFrameInvalidationHevcPrefixes.add("omx.qcom")
                    refFrameInvalidationHevcPrefixes.add("c2.qti")
                }
            } else {
                refFrameInvalidationAvcPrefixes.remove("omx.qcom")
                refFrameInvalidationAvcPrefixes.remove("c2.qti")
            }

            if (glesVersion >= 0x30001) {
                whitelistedHevcDecoders.add("omx.hisi")
            } else {
                useThreeBuffersPrefixes.add("omx.qcom")
                useThreeBuffersPrefixes.add("c2.qti")
            }
        }

        if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
            refFrameInvalidationHevcPrefixes.add("omx.mtk")
            refFrameInvalidationHevcPrefixes.add("c2.mtk")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                refFrameInvalidationHevcPrefixes.add("omx.amlogic")
                refFrameInvalidationHevcPrefixes.add("c2.amlogic")
            }
        }

        initialized = true
    }

    private fun isDecoderInList(decoderList: List<String>, decoderName: String): Boolean {
        for (badPrefix in decoderList) {
            if (decoderName.length >= badPrefix.length) {
                val prefix = decoderName.substring(0, badPrefix.length)
                if (prefix.equals(badPrefix, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    fun getDecoders(): List<MediaCodecInfo> {
        val decoderList = LinkedList<MediaCodecInfo>()
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) {
                decoderList.add(codecInfo)
            }
        }
        return decoderList
    }

    fun findFirstDecoder(mimeType: String): MediaCodecInfo? {
        for (codecInfo in getDecoders()) {
            for (type in codecInfo.supportedTypes) {
                if (type.equals(mimeType, ignoreCase = true)) {
                    return codecInfo
                }
            }
        }
        return null
    }

    fun findProbableSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        return findKnownSafeDecoder(mimeType, requiredProfile)
            ?: try {
                findFirstValidDecoder(mimeType, requiredProfile)
            } catch (_: Exception) {
                null
            }
    }

    private fun findFirstValidDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        for (codecInfo in getDecoders()) {
            for (type in codecInfo.supportedTypes) {
                if (type.equals(mimeType, ignoreCase = true) &&
                    !isDecoderInList(blacklistedDecoderPrefixes, codecInfo.name)
                ) {
                    if (requiredProfile != -1) {
                        val caps = codecInfo.getCapabilitiesForType(type)
                        for (profile in caps.profileLevels) {
                            if (profile.profile == requiredProfile) {
                                return codecInfo
                            }
                        }
                    } else {
                        return codecInfo
                    }
                }
            }
        }
        return null
    }

    private fun findKnownSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        // Two rounds: round 0 looks for official FEATURE_LowLatency support (Android 11+),
        // round 1 accepts any preferred decoder.
        for (i in 0..1) {
            for (codecInfo in getDecoders()) {
                for (type in codecInfo.supportedTypes) {
                    if (type.equals(mimeType, ignoreCase = true) &&
                        isDecoderInList(preferredDecoders, codecInfo.name)
                    ) {
                        val caps = codecInfo.getCapabilitiesForType(type)

                        if (i == 0 && !decoderSupportsAndroidRLowLatency(codecInfo, type)) {
                            continue
                        }

                        if (requiredProfile != -1) {
                            for (profile in caps.profileLevels) {
                                if (profile.profile == requiredProfile) {
                                    return codecInfo
                                }
                            }
                        } else {
                            return codecInfo
                        }
                    }
                }
            }
        }
        return null
    }

    fun isExynos4Device(): Boolean {
        try {
            val cpuInfo = File("/proc/cpuinfo")
            BufferedReader(FileReader(cpuInfo)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.lowercase(Locale.ROOT).contains("smdk4")) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    fun decoderIsDirectSubmit(decoderName: String): Boolean {
        return isDecoderInList(directSubmitPrefixes, decoderName) && !isExynos4Device()
    }

    fun decoderNeedsSpsBitstreamRestrictions(decoderName: String): Boolean {
        return isDecoderInList(spsFixupBitstreamFixupDecoderPrefixes, decoderName)
    }

    fun decoderNeedsBaselineSpsHack(decoderName: String): Boolean {
        return isDecoderInList(baselineProfileHackPrefixes, decoderName)
    }

    fun getDecoderOptimalSlicesPerFrame(decoderName: String): Byte {
        return if (isDecoderInList(blacklistedDecoderPrefixes, decoderName) ||
            isDecoderInList(useThreeBuffersPrefixes, decoderName)
        ) {
            4
        } else {
            1
        }
    }

    fun decoderSupportsRefFrameInvalidationAvc(decoderName: String, videoHeight: Int): Boolean {
        if (videoHeight > 720 && isLowEndSnapdragon) {
            return false
        }
        if (Build.DEVICE == "b3" || Build.DEVICE == "b5") {
            return false
        }
        return isDecoderInList(refFrameInvalidationAvcPrefixes, decoderName)
    }

    fun decoderSupportsRefFrameInvalidationHevc(decoderInfo: MediaCodecInfo): Boolean {
        if (decoderSupportsKnownLowLatencyOptions(decoderInfo, "video/hevc")) {
            return true
        }
        return isDecoderInList(refFrameInvalidationHevcPrefixes, decoderInfo.name)
    }

    fun decoderSupportsRefFrameInvalidationAv1(decoderInfo: MediaCodecInfo): Boolean {
        return decoderSupportsKnownLowLatencyOptions(decoderInfo, "video/av01")
    }

    fun decoderIsWhitelistedForHevc(decoderInfo: MediaCodecInfo): Boolean {
        if (isSoftwareDecoder(decoderInfo)) {
            return false
        }
        if (Build.MANUFACTURER.equals("google", ignoreCase = true) ||
            Build.MANUFACTURER.equals("motorola", ignoreCase = true)
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated(decoderInfo)) {
            return true
        }
        return isDecoderInList(whitelistedHevcDecoders, decoderInfo.name)
    }

    fun isDecoderWhitelistedForAv1(decoderInfo: MediaCodecInfo): Boolean {
        if (isSoftwareDecoder(decoderInfo)) {
            return false
        }
        if (decoderInfo.name.equals("c2.exynos.av1.decoder", ignoreCase = true) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            return false
        }
        if (Build.MANUFACTURER.equals("google", ignoreCase = true) ||
            Build.MANUFACTURER.equals("motorola", ignoreCase = true)
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated(decoderInfo)) {
            return true
        }
        return false
    }

    private fun isSoftwareDecoder(decoderInfo: MediaCodecInfo): Boolean {
        if (isDecoderInList(blacklistedDecoderPrefixes, decoderInfo.name)) {
            return true
        }
        if (decoderInfo.name.lowercase(Locale.ROOT).contains("sw")) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return decoderInfo.isSoftwareOnly || !decoderInfo.isHardwareAccelerated
        }
        return false
    }

    private fun isHardwareAccelerated(decoderInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return decoderInfo.isHardwareAccelerated
        }
        return !isSoftwareDecoder(decoderInfo)
    }

    fun decoderCanMeetPerformancePoint(
        decoderInfo: MediaCodecInfo,
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int
    ): Boolean {
        val caps = try {
            decoderInfo.getCapabilitiesForType(mimeType)
        } catch (_: Exception) {
            return false
        }
        val videoCaps = caps.videoCapabilities ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetPerfPoint = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps)
            val perfPoints = videoCaps.supportedPerformancePoints
            if (perfPoints != null) {
                for (perfPoint in perfPoints) {
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true
                    }
                }
                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val frameRates = videoCaps.getAchievableFrameRatesFor(width, height)
                if (frameRates != null) {
                    return fps <= frameRates.upper
                }
            } catch (_: IllegalArgumentException) {
                return false
            }
        }

        return videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
    }

    fun decoderSupportsAdaptivePlayback(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        if (isDecoderInList(blacklistedAdaptivePlaybackPrefixes, decoderInfo.name)) {
            return false
        }
        return try {
            decoderInfo.getCapabilitiesForType(mimeType)
                .isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)
        } catch (_: Exception) {
            false
        }
    }

    fun decoderSupportsFusedIdrFrame(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        return decoderSupportsAdaptivePlayback(decoderInfo, mimeType)
    }

    fun decoderSupportsAndroidRLowLatency(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (decoderInfo.getCapabilitiesForType(mimeType)
                        .isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)
                ) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun decoderSupportsKnownLowLatencyOptions(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        if (decoderSupportsAndroidRLowLatency(decoderInfo, mimeType)) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val codec = android.media.MediaCodec.createByCodecName(decoderInfo.name)
                try {
                    for (option in codec.supportedVendorParameters) {
                        if (isDecoderInList(knownVendorLowLatencyOptions, option)) {
                            return true
                        }
                    }
                } finally {
                    codec.release()
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun decoderSupportsMaxOperatingRate(decoderName: String): Boolean {
        return !isAdreno620 && (isDecoderInList(
            Collections.singletonList("omx.qcom"),
            decoderName
        ) || isDecoderInList(Collections.singletonList("c2.qti"), decoderName))
    }

    @SuppressLint("InlinedApi")
    fun setDecoderLowLatencyOptions(
        videoFormat: MediaFormat,
        decoderInfo: MediaCodecInfo,
        mimeType: String,
        tryNumber: Int
    ): Boolean {
        var newOptions = false

        if (tryNumber < 1) {
            videoFormat.setInteger("low-latency", 1)
            newOptions = true

            if (decoderSupportsAndroidRLowLatency(decoderInfo, mimeType)) {
                return true
            }
        }

        if (tryNumber < 2 &&
            (!Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
                Build.VERSION.SDK_INT > Build.VERSION_CODES.M)
        ) {
            videoFormat.setInteger("vdec-lowlatency", 1)
            newOptions = true
        }

        if (tryNumber < 3) {
            if (decoderSupportsMaxOperatingRate(decoderInfo.name)) {
                videoFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                newOptions = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                newOptions = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isDecoderInList(Collections.singletonList("omx.qcom"), decoderInfo.name) ||
                isDecoderInList(Collections.singletonList("c2.qti"), decoderInfo.name)
            ) {
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                    newOptions = true
                }
                if (tryNumber < 5) {
                    videoFormat.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                    newOptions = true
                }
            } else if (isDecoderInList(Collections.singletonList("omx.hisi"), decoderInfo.name) ||
                isDecoderInList(Collections.singletonList("c2.hisi"), decoderInfo.name)
            ) {
                if (tryNumber < 4) {
                    videoFormat.setInteger(
                        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
                        1
                    )
                    newOptions = true
                }
                if (tryNumber < 5) {
                    videoFormat.setInteger(
                        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy",
                        -1
                    )
                    newOptions = true
                }
            } else if (isDecoderInList(Collections.singletonList("omx.exynos"), decoderInfo.name) ||
                isDecoderInList(Collections.singletonList("c2.exynos"), decoderInfo.name)
            ) {
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                    newOptions = true
                }
            } else if (isDecoderInList(Collections.singletonList("omx.amlogic"), decoderInfo.name) ||
                isDecoderInList(Collections.singletonList("c2.amlogic"), decoderInfo.name)
            ) {
                if (tryNumber < 4) {
                    videoFormat.setInteger("vendor.low-latency.enable", 1)
                    newOptions = true
                }
            }
        }

        return newOptions
    }

    /**
     * Probed codec support result for a given stream configuration and display.
     */
    data class CodecCapabilityReport(
        val avcDecoder: MediaCodecInfo?,
        val hevcDecoder: MediaCodecInfo?,
        val av1Decoder: MediaCodecInfo?,
        val supportedVideoFormats: Int,
        val videoCapabilities: Int
    )

    fun probeCapabilities(
        context: Context,
        requestedCodecMask: Int,
        width: Int,
        height: Int,
        fps: Int,
        enableHdr: Boolean,
        enableYuv444: Boolean,
        displaySupportsHdr10: Boolean
    ): CodecCapabilityReport {
        initialize(context)

        val avcDecoder = findProbableSafeDecoder("video/avc", CodecProfileLevel.AVCProfileHigh)
            ?: findFirstDecoder("video/avc")

        val hevcDecoder = findProbableSafeDecoder("video/hevc", -1)?.let { candidate ->
            if (decoderIsWhitelistedForHevc(candidate)) {
                candidate
            } else if (avcDecoder != null &&
                !decoderCanMeetPerformancePoint(avcDecoder, "video/avc", width, height, fps) &&
                decoderCanMeetPerformancePoint(candidate, "video/hevc", width, height, fps)
            ) {
                candidate
            } else {
                null
            }
        }

        val av1Decoder = findProbableSafeDecoder("video/av01", -1)?.let { candidate ->
            if (isDecoderWhitelistedForAv1(candidate)) {
                candidate
            } else if (hevcDecoder != null &&
                !decoderCanMeetPerformancePoint(hevcDecoder, "video/hevc", width, height, fps) &&
                decoderCanMeetPerformancePoint(candidate, "video/av01", width, height, fps)
            ) {
                candidate
            } else if (avcDecoder != null &&
                !decoderCanMeetPerformancePoint(avcDecoder, "video/avc", width, height, fps) &&
                decoderCanMeetPerformancePoint(candidate, "video/av01", width, height, fps)
            ) {
                candidate
            } else {
                null
            }
        }

        var formats = com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H264
        if (enableYuv444 && (requestedCodecMask and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H264_HIGH8_444) != 0) {
            formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H264_HIGH8_444
        }

        val wantsHevc = (requestedCodecMask and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_MASK_H265) != 0
        if (hevcDecoder != null && wantsHevc) {
            formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H265
            if (enableYuv444 && (requestedCodecMask and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H265_REXT8_444) != 0) {
                formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H265_REXT8_444
            }
            if (enableHdr && displaySupportsHdr10) {
                val hevc10Decoder = findProbableSafeDecoder("video/hevc", CodecProfileLevel.HEVCProfileMain10HDR10)
                    ?: findProbableSafeDecoder("video/hevc", CodecProfileLevel.HEVCProfileMain10)
                if (hevc10Decoder != null) {
                    formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H265_MAIN10
                    if (enableYuv444 && (requestedCodecMask and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H265_REXT10_444) != 0) {
                        formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H265_REXT10_444
                    }
                }
            }
        }

        val wantsAv1 = (requestedCodecMask and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0
        if (av1Decoder != null && wantsAv1) {
            formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_AV1_MAIN8
            if (enableYuv444 && (requestedCodecMask and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_AV1_HIGH8_444) != 0) {
                formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_AV1_HIGH8_444
            }
            if (enableHdr && displaySupportsHdr10 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val av110Decoder = findProbableSafeDecoder("video/av01", CodecProfileLevel.AV1ProfileMain10HDR10)
                    ?: findProbableSafeDecoder("video/av01", CodecProfileLevel.AV1ProfileMain10)
                if (av110Decoder != null) {
                    formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_AV1_MAIN10
                    if (enableYuv444 && (requestedCodecMask and com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_AV1_HIGH10_444) != 0) {
                        formats = formats or com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_AV1_HIGH10_444
                    }
                }
            }
        }

        // Intersect with user-requested mask, always preserving H.264 fallback if everything else was filtered out
        val finalFormats = (formats and requestedCodecMask).let {
            if (it == 0) com.moonlight.material.bridge.MoonBridge.VIDEO_FORMAT_H264 else it
        }

        var capabilities = 0
        if (avcDecoder != null) {
            if (decoderIsDirectSubmit(avcDecoder.name)) {
                capabilities = capabilities or com.moonlight.material.bridge.MoonBridge.CAPABILITY_DIRECT_SUBMIT
            }
            if (decoderSupportsRefFrameInvalidationAvc(avcDecoder.name, height)) {
                capabilities = capabilities or com.moonlight.material.bridge.MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC
            }
            capabilities = capabilities or com.moonlight.material.bridge.MoonBridge.CAPABILITY_SLICES_PER_FRAME(
                getDecoderOptimalSlicesPerFrame(avcDecoder.name)
            )
        }
        if (hevcDecoder != null && decoderSupportsRefFrameInvalidationHevc(hevcDecoder)) {
            capabilities = capabilities or com.moonlight.material.bridge.MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
        }
        if (av1Decoder != null && decoderSupportsRefFrameInvalidationAv1(av1Decoder)) {
            capabilities = capabilities or com.moonlight.material.bridge.MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1
        }

        return CodecCapabilityReport(
            avcDecoder = avcDecoder,
            hevcDecoder = hevcDecoder,
            av1Decoder = av1Decoder,
            supportedVideoFormats = finalFormats,
            videoCapabilities = capabilities
        )
    }
}
