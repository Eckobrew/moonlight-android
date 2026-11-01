package com.moonlight.material.bridge

import androidx.annotation.Keep

/**
 * JNI Bridge to moonlight-common-c and Opus.
 */
@Keep
object MoonBridge {

    interface ConnectionListener {
        fun onStageStarting(stage: Int)
        fun onStageComplete(stage: Int)
        fun onStageFailed(stage: Int, errorCode: Int)
        fun onConnectionStarted()
        fun onConnectionTerminated(errorCode: Int)
        fun onRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {}
        fun onConnectionStatusUpdate(connectionStatus: Int) {}
        fun onSetHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {}
        fun onRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {}
        fun onSetMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {}
        fun onSetControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {}
    }

    interface VideoDecoderRendererListener {
        fun onSetup(videoFormat: Int, width: Int, height: Int, redrawRate: Int): Int
        fun onStart()
        fun onStop()
        fun onCleanup()
        fun onSubmitDecodeUnit(
            frameData: ByteArray,
            length: Int,
            bufferType: Int,
            frameNumber: Int,
            frameType: Int,
            frameHostProcessingLatency: Char,
            receiveTimeUs: Long,
            enqueueTimeUs: Long
        ): Int
    }

    interface AudioRendererListener {
        fun onInit(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int
        fun onStart()
        fun onStop()
        fun onCleanup()
        fun onPlaySample(sampleData: ShortArray)
    }

    var connectionListener: ConnectionListener? = null
    var videoDecoderListener: VideoDecoderRendererListener? = null
    var audioRendererListener: AudioRendererListener? = null

    // Decoder return codes
    const val DR_OK = 0
    const val DR_NEED_IDR = -1

    // Buffer types
    const val BUFFER_TYPE_PICDATA = 0x00
    const val BUFFER_TYPE_SPS = 0x01
    const val BUFFER_TYPE_PPS = 0x02
    const val BUFFER_TYPE_VPS = 0x03

    // Frame types
    const val FRAME_TYPE_PFRAME = 0x00
    const val FRAME_TYPE_IDR = 0x01

    // Video formats
    const val VIDEO_FORMAT_H264 = 0x0001
    const val VIDEO_FORMAT_H264_HIGH8_444 = 0x0004
    const val VIDEO_FORMAT_H265 = 0x0100
    const val VIDEO_FORMAT_H265_MAIN10 = 0x0200
    const val VIDEO_FORMAT_H265_REXT8_444 = 0x0400
    const val VIDEO_FORMAT_H265_REXT10_444 = 0x0800
    const val VIDEO_FORMAT_AV1_MAIN8 = 0x1000
    const val VIDEO_FORMAT_AV1_MAIN10 = 0x2000
    const val VIDEO_FORMAT_AV1_HIGH8_444 = 0x4000
    const val VIDEO_FORMAT_AV1_HIGH10_444 = 0x8000

    // Format masks
    const val VIDEO_FORMAT_MASK_H264 = 0x000F
    const val VIDEO_FORMAT_MASK_H265 = 0x0F00
    const val VIDEO_FORMAT_MASK_AV1 = 0xF000
    const val VIDEO_FORMAT_MASK_10BIT = 0xAA00
    const val VIDEO_FORMAT_MASK_YUV444 = 0xCC04

    // Audio configurations
    const val AUDIO_CONFIGURATION_STEREO = (0x3 shl 16) or (2 shl 8) or 0xCA
    const val AUDIO_CONFIGURATION_51_SURROUND = (0x3F shl 16) or (6 shl 8) or 0xCA
    const val AUDIO_CONFIGURATION_71_SURROUND = (0x63F shl 16) or (8 shl 8) or 0xCA

    fun channelCountFromAudioConfiguration(config: Int): Int = (config shr 8) and 0xFF
    fun channelMaskFromAudioConfiguration(config: Int): Int = (config shr 16) and 0xFFFF

    init {
        try {
            System.loadLibrary("moonlight-bridge")
            init()
        } catch (e: UnsatisfiedLinkError) {
            // Expected during host JVM unit tests if lib is not in java.library.path
        }
    }

    // --- Native Callback Handlers (invoked by JNI) ---

    @JvmStatic
    @Keep
    fun bridgeDrSetup(videoFormat: Int, width: Int, height: Int, redrawRate: Int): Int {
        return videoDecoderListener?.onSetup(videoFormat, width, height, redrawRate) ?: -1
    }

    @JvmStatic
    @Keep
    fun bridgeDrStart() {
        videoDecoderListener?.onStart()
    }

    @JvmStatic
    @Keep
    fun bridgeDrStop() {
        videoDecoderListener?.onStop()
    }

    @JvmStatic
    @Keep
    fun bridgeDrCleanup() {
        videoDecoderListener?.onCleanup()
    }

    @JvmStatic
    @Keep
    fun bridgeDrSubmitDecodeUnit(
        frameData: ByteArray,
        length: Int,
        bufferType: Int,
        frameNumber: Int,
        frameType: Int,
        frameHostProcessingLatency: Char,
        receiveTimeUs: Long,
        enqueueTimeUs: Long
    ): Int {
        return videoDecoderListener?.onSubmitDecodeUnit(
            frameData, length, bufferType, frameNumber, frameType,
            frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs
        ) ?: 0
    }

    @JvmStatic
    @Keep
    fun bridgeArInit(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int {
        return audioRendererListener?.onInit(audioConfiguration, sampleRate, samplesPerFrame) ?: -1
    }

    @JvmStatic
    @Keep
    fun bridgeArStart() {
        audioRendererListener?.onStart()
    }

    @JvmStatic
    @Keep
    fun bridgeArStop() {
        audioRendererListener?.onStop()
    }

    @JvmStatic
    @Keep
    fun bridgeArCleanup() {
        audioRendererListener?.onCleanup()
    }

    @JvmStatic
    @Keep
    fun bridgeArPlaySample(sampleData: ShortArray) {
        audioRendererListener?.onPlaySample(sampleData)
    }

    @JvmStatic
    @Keep
    fun bridgeClStageStarting(stage: Int) {
        connectionListener?.onStageStarting(stage)
    }

    @JvmStatic
    @Keep
    fun bridgeClStageComplete(stage: Int) {
        connectionListener?.onStageComplete(stage)
    }

    @JvmStatic
    @Keep
    fun bridgeClStageFailed(stage: Int, errorCode: Int) {
        connectionListener?.onStageFailed(stage, errorCode)
    }

    @JvmStatic
    @Keep
    fun bridgeClConnectionStarted() {
        connectionListener?.onConnectionStarted()
    }

    @JvmStatic
    @Keep
    fun bridgeClConnectionTerminated(errorCode: Int) {
        connectionListener?.onConnectionTerminated(errorCode)
    }

    @JvmStatic
    @Keep
    fun bridgeClRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        connectionListener?.onRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    @JvmStatic
    @Keep
    fun bridgeClConnectionStatusUpdate(connectionStatus: Int) {
        connectionListener?.onConnectionStatusUpdate(connectionStatus)
    }

    @JvmStatic
    @Keep
    fun bridgeClSetHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        connectionListener?.onSetHdrMode(enabled, hdrMetadata)
    }

    @JvmStatic
    @Keep
    fun bridgeClRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        connectionListener?.onRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    @JvmStatic
    @Keep
    fun bridgeClSetMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        connectionListener?.onSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }

    @JvmStatic
    @Keep
    fun bridgeClSetControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        connectionListener?.onSetControllerLED(controllerNumber, r, g, b)
    }

    // --- Native Methods ---

    @JvmStatic
    external fun init()

    @JvmStatic
    external fun startConnection(
        address: String,
        appVersion: String,
        gfeVersion: String?,
        rtspSessionUrl: String?,
        serverCodecModeSupport: Int,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        packetSize: Int,
        streamingRemotely: Int,
        audioConfiguration: Int,
        supportedVideoFormats: Int,
        clientRefreshRateX100: Int,
        riAesKey: ByteArray,
        riAesIv: ByteArray,
        videoCapabilities: Int,
        colorSpace: Int,
        colorRange: Int
    ): Int

    @JvmStatic
    external fun stopConnection()

    @JvmStatic
    external fun interruptConnection()

    @JvmStatic
    external fun getStageName(stage: Int): String

    @JvmStatic
    external fun getPortFlagsFromStage(stage: Int): Int

    @JvmStatic
    external fun getPortFlagsFromTerminationErrorCode(errorCode: Int): Int

    @JvmStatic
    external fun stringifyPortFlags(portFlags: Int, separator: String): String

    @JvmStatic
    external fun testClientConnectivity(testServerHostName: String, referencePort: Int, testFlags: Int): Int

    @JvmStatic
    external fun getEstimatedRttInfo(): Long

    @JvmStatic
    external fun getLaunchUrlQueryParameters(): String

    @JvmStatic
    external fun getHostFeatureFlags(): Int

    @JvmStatic
    external fun getPendingAudioDuration(): Int

    @JvmStatic
    external fun getPendingVideoFrames(): Int

    @JvmStatic
    external fun findExternalAddressIP4(stunHostName: String, stunPort: Int): String?

    @JvmStatic
    external fun sendHostCommand(commandIndex: Byte): Int

    @JvmStatic
    external fun sendMouseMove(deltaX: Short, deltaY: Short)

    @JvmStatic
    external fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short)

    @JvmStatic
    external fun sendMouseMoveAsMousePosition(deltaX: Short, deltaY: Short, referenceWidth: Short, referenceHeight: Short)

    @JvmStatic
    external fun sendMouseButton(buttonEvent: Byte, mouseButton: Byte)

    @JvmStatic
    external fun sendMouseHighResScroll(scrollAmount: Short)

    @JvmStatic
    external fun sendMouseHighResHScroll(scrollAmount: Short)

    @JvmStatic
    external fun sendKeyboardInput(keyCode: Short, keyAction: Byte, modifiers: Byte, flags: Byte)

    @JvmStatic
    fun sendUtf8Text(text: String) = sendUtf8TextBytes(text.toByteArray(Charsets.UTF_8))

    @JvmStatic
    external fun sendUtf8TextBytes(text: ByteArray): Int

    @JvmStatic
    external fun sendMultiControllerInput(
        controllerNumber: Short,
        activeGamepadMask: Short,
        buttonFlags: Int,
        leftTrigger: Byte,
        rightTrigger: Byte,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short
    )

    @JvmStatic
    external fun sendTouchEvent(
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Short
    ): Int

    @JvmStatic
    external fun sendPenEvent(
        eventType: Byte,
        toolType: Byte,
        penButtons: Byte,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Short,
        tilt: Byte
    ): Int

    @JvmStatic
    external fun sendControllerArrivalEvent(
        controllerNumber: Byte,
        activeGamepadMask: Short,
        type: Byte,
        supportedButtonFlags: Int,
        capabilities: Short
    ): Int

    @JvmStatic
    external fun sendControllerTouchEvent(
        controllerNumber: Byte,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float
    ): Int

    @JvmStatic
    external fun sendControllerMotionEvent(
        controllerNumber: Byte,
        motionType: Byte,
        x: Float,
        y: Float,
        z: Float
    ): Int

    @JvmStatic
    external fun sendControllerBatteryEvent(
        controllerNumber: Byte,
        batteryState: Byte,
        batteryPercentage: Byte
    ): Int

    @JvmStatic
    external fun requestIdrFrame()

    @JvmStatic
    external fun guessControllerType(vendorId: Int, productId: Int): Byte

    @JvmStatic
    external fun guessControllerHasPaddles(vendorId: Int, productId: Int): Boolean

    @JvmStatic
    external fun guessControllerHasShareButton(vendorId: Int, productId: Int): Boolean
}
