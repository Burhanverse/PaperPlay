package io.max.paperplay.player.usb

import io.max.paperplay.player.PlayLog
import org.json.JSONObject
import java.nio.ByteBuffer

data class DacCapabilities(
    val sampleRates: List<Int>,
    val bitDepths: List<Int>,
    val channelCounts: List<Int>,
    val clockSourceId: Int,
    val uacVersion: Int,
    val volumeMinDb: Float,
    val volumeMaxDb: Float,
    val volumeResDb: Float,
    val hasVolume: Boolean,
    val hasMute: Boolean
)

/**
 * JNI bindings for the native USB audio streaming engine (`engine/src/usb/`).
 */
internal object UsbEngineNative : UsbEngineBridge {
    private var loadAttempted = false
    private var isLoaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!loadAttempted) {
            loadAttempted = true
            isLoaded = runCatching {
                System.loadLibrary("usb-1.0")
                System.loadLibrary("usb_audio")
                true
            }.onSuccess {
                PlayLog.log("usb", "Successfully loaded libusb-1.0 and libusb_audio")
                UsbRingLog.record("UsbEngineNative", "Native USB libraries loaded successfully", UsbRingLog.CODE_GENERIC)
            }.onFailure {
                PlayLog.log("usb", "Failed to load native USB libraries: ${it.message}")
                UsbRingLog.record("UsbEngineNative", "Native library load failed: ${it.message}\n${it.stackTraceToString()}", UsbRingLog.CODE_ERROR)
            }.getOrDefault(false)
        }
        return isLoaded
    }

    override val available: Boolean
        get() = ensureLoaded()

    val isAlreadyLoaded: Boolean
        get() = isLoaded

    fun parseCapabilities(fd: Int, desc: UsbAudioDeviceDescription? = null): DacCapabilities? {
        if (!available) return null
        val pbAlt = desc?.playbackAltSettings?.firstOrNull()
        val acIface = desc?.acInterfaceNumber ?: 0
        val streamIface = pbAlt?.interfaceNumber ?: 1
        val isUac2 = desc?.uacVersion != UacVersion.UAC_1_0
        val clockId = (if (pbAlt != null) desc.resolvePlaybackClockSourceId(pbAlt) else null) ?: 9
        val fu = desc?.resolvePlaybackFeatureUnit(pbAlt)
        val fuId = fu?.id ?: 0
        val fuHasVol = fu?.let { it.hasMasterVolume || it.hasChannelVolume } ?: false
        val fuHasMasterVol = fu?.hasMasterVolume ?: false
        val fuHasMute = fu?.hasMasterMute ?: false

        val jsonStr = runCatching {
            probeDac(fd, acIface, streamIface, isUac2, clockId, fuId, fuHasVol, fuHasMasterVol, fuHasMute)
        }.getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(jsonStr)
            val ratesArr = obj.getJSONArray("rates")
            val nativeRates = (0 until ratesArr.length()).map { ratesArr.getInt(it) }
            val uac1Rates = desc?.playbackAltSettings
                ?.flatMap { alt ->
                    alt.sampleRates + (alt.continuousRateRange?.let { (lo, hi) ->
                        listOf(44100, 48000, 88200, 96000, 176400, 192000).filter { it in lo..hi }
                    } ?: emptyList())
                }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
            val rates = if (isUac2 || nativeRates.isNotEmpty()) nativeRates else uac1Rates

            val depthsArr = obj.getJSONArray("depths")
            val depths = desc?.playbackAltSettings?.map { it.bitResolution }?.distinct()?.sorted()
                ?.takeIf { it.isNotEmpty() }
                ?: (0 until depthsArr.length()).map { depthsArr.getInt(it) }

            val chArr = obj.getJSONArray("channels")
            val channels = desc?.playbackAltSettings?.map { it.channelCount }?.distinct()?.sorted()
                ?.takeIf { it.isNotEmpty() }
                ?: (0 until chArr.length()).map { chArr.getInt(it) }

            DacCapabilities(
                sampleRates = rates,
                bitDepths = depths,
                channelCounts = channels,
                clockSourceId = clockId,
                uacVersion = obj.optInt("uac", if (isUac2) 2 else 1),
                volumeMinDb = obj.optDouble("volMin", -64.0).toFloat(),
                volumeMaxDb = obj.optDouble("volMax", 0.0).toFloat(),
                volumeResDb = obj.optDouble("volRes", 0.5).toFloat(),
                hasVolume = fuHasVol || obj.optBoolean("hasVol", false),
                hasMute = fuHasMute || obj.optBoolean("hasMute", false)
            )
        }.onFailure {
            PlayLog.log("usb", "Failed to parse DacCapabilities JSON: $it")
            UsbRingLog.record("UsbEngineNative", "Failed to parse DacCapabilities JSON: $it", UsbRingLog.CODE_ERROR)
        }.getOrNull()
    }

    override external fun create(
        fd: Int,
        endpoint: Int,
        streamingIface: Int,
        targetAlt: Int,
        isUac2: Boolean,
        clockSourceId: Int,
        controlIface: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        subslotSize: Int,
        isHighSpeed: Boolean,
        isDop: Boolean,
        feedbackEp: Int
    ): Long

    override external fun reconfigure(
        handle: Long,
        endpoint: Int,
        streamingIface: Int,
        targetAlt: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        subslotSize: Int,
        isHighSpeed: Boolean,
        isDop: Boolean,
        feedbackEp: Int
    ): Int

    override external fun destroy(handle: Long)
    override external fun start(handle: Long): Int
    override external fun stop(handle: Long): Int
    override external fun pause(handle: Long): Int
    override external fun resume(handle: Long): Int
    override external fun flush(handle: Long): Int
    override external fun write(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    override external fun getStats(handle: Long, outStats: LongArray)
    override external fun playDebugTest(handle: Long, testType: Int, durationMs: Int): Int
    override external fun setHardwareVolume(
        handle: Long,
        featureUnitId: Int,
        controlIface: Int,
        channel: Int,
        volumeRaw: Int
    ): Int
    override external fun setHardwareMute(
        handle: Long,
        featureUnitId: Int,
        controlIface: Int,
        channel: Int,
        mute: Boolean
    ): Int
    override external fun getCurrentRate(handle: Long): Int
    override external fun getCurrentAlt(handle: Long): Int
    override external fun restoreDefaultVolume(handle: Long): Int

    external fun sessionOpen(
        fd: Int,
        controlIface: Int,
        streamingIface: Int,
        isUac2: Boolean,
        clockSourceId: Int,
        hasHardwareVolume: Boolean,
        featureUnitId: Int,
    ): Long

    external fun sessionReconfigure(
        handle: Long,
        endpoint: Int,
        streamingIface: Int,
        targetAlt: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        subslotSize: Int,
        isHighSpeed: Boolean,
        isDop: Boolean,
        feedbackEp: Int,
    ): Int

    external fun sessionApplyVolume(
        handle: Long,
        featureUnitId: Int,
        controlIface: Int,
        channel: Int,
        volumeRaw: Int,
        mute: Boolean,
    ): Int

    external fun sessionStart(handle: Long): Int
    external fun sessionPauseGrace(handle: Long): Int
    external fun sessionStop(handle: Long): Int
    external fun sessionWritePcm(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    external fun sessionSetMuteRamp(handle: Long, muted: Boolean): Int
    external fun sessionFlush(handle: Long): Int
    external fun sessionGetStats(handle: Long, outStats: LongArray): Int
    external fun sessionClose(handle: Long)

    external fun reattachKernelDriver(fd: Int, interfaceNumber: Int): Int
    external fun controlTransfer(
        fd: Int,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int
    ): Int

    override external fun probeDac(
        fd: Int,
        acIface: Int,
        streamingIface: Int,
        isUac2: Boolean,
        clockSourceId: Int,
        featureUnitId: Int,
        fuHasVolume: Boolean,
        fuHasMasterVolume: Boolean,
        fuHasMute: Boolean,
    ): String?

    override fun getNativeLog(): String? {
        if (!available) return null
        return runCatching { dumpNativeLog() }.getOrNull()
    }

    private external fun dumpNativeLog(): String?
}

