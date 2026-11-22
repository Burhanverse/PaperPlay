package io.max.paperplay.player.usb

import java.nio.ByteBuffer

/**
 * Coarse native USB session interface mirroring Stage 2 JNI `UsbEngineNative.session*`.
 * Implemented directly over JNI on Android and faked cleanly in JVM unit tests.
 */
interface CoarseUsbSessionApi {
    val available: Boolean
    fun open(
        fd: Int,
        controlIface: Int,
        streamingIface: Int,
        isUac2: Boolean,
        clockSourceId: Int,
        hasHardwareVolume: Boolean,
        featureUnitId: Int,
    ): Long

    fun reconfigure(
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

    fun applyVolume(
        handle: Long,
        featureUnitId: Int,
        controlIface: Int,
        channel: Int,
        volumeRaw: Int,
        mute: Boolean,
    ): Int

    fun start(handle: Long): Int
    fun pauseGrace(handle: Long): Int
    fun stop(handle: Long): Int
    fun writePcm(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    fun setMuteRamp(handle: Long, muted: Boolean): Int
    fun flush(handle: Long): Int
    fun escalateCushionAndResync(handle: Long): Int = 0
    fun getStats(handle: Long, outStats: LongArray): Int
    fun close(handle: Long)
}

/**
 * Hearing-safety guard: non-user automatic volume syncs may NEVER raise stream/DAC
 * volume by more than +1 discrete step in a single sync cycle.
 */
fun clampAutoVolumeStep(currentIndex: Int?, targetIndex: Int, isUser: Boolean): Int {
    if (isUser || currentIndex == null) return targetIndex
    return if (targetIndex > currentIndex + 1) currentIndex + 1 else targetIndex
}
