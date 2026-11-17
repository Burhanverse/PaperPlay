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

    fun parseCapabilities(fd: Int): DacCapabilities? {
        if (!available) return null
        val jsonStr = runCatching { probeDac(fd) }.getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(jsonStr)
            val ratesArr = obj.getJSONArray("rates")
            val rates = (0 until ratesArr.length()).map { ratesArr.getInt(it) }

            val depthsArr = obj.getJSONArray("depths")
            val depths = (0 until depthsArr.length()).map { depthsArr.getInt(it) }

            val chArr = obj.getJSONArray("channels")
            val channels = (0 until chArr.length()).map { chArr.getInt(it) }

            DacCapabilities(
                sampleRates = rates,
                bitDepths = depths,
                channelCounts = channels,
                clockSourceId = obj.optInt("clockId", 9),
                uacVersion = obj.optInt("uac", 2),
                volumeMinDb = obj.optDouble("volMin", -64.0).toFloat(),
                volumeMaxDb = obj.optDouble("volMax", 0.0).toFloat(),
                volumeResDb = obj.optDouble("volRes", 0.5).toFloat(),
                hasVolume = obj.optBoolean("hasVol", false),
                hasMute = obj.optBoolean("hasMute", false)
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

    override external fun probeDac(fd: Int): String?

    override fun getNativeLog(): String? {
        if (!available) return null
        return runCatching { dumpNativeLog() }.getOrNull()
    }

    private external fun dumpNativeLog(): String?
}

