package io.max.paperplay.player.usb

import java.nio.ByteBuffer

/**
 * Interface abstracting the native Rust USB audio engine calls.
 * Enables unit testing of format switching, readbacks, and error recovery without hardware.
 */
interface UsbEngineBridge {
    fun create(
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
        feedbackEp: Int
    ): Int = -1

    fun destroy(handle: Long)
    fun start(handle: Long): Int
    fun stop(handle: Long): Int
    fun pause(handle: Long): Int
    fun resume(handle: Long): Int
    fun flush(handle: Long): Int
    fun write(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    fun getStats(handle: Long, outStats: LongArray)
    fun playDebugTest(handle: Long, testType: Int, durationMs: Int): Int
    fun setHardwareVolume(handle: Long, featureUnitId: Int, controlIface: Int, channel: Int, volumeRaw: Int): Int
    fun setHardwareMute(handle: Long, featureUnitId: Int, controlIface: Int, channel: Int, mute: Boolean): Int
    fun getCurrentRate(handle: Long): Int
    fun getCurrentAlt(handle: Long): Int
    val available: Boolean get() = true
    fun restoreDefaultVolume(handle: Long): Int
    fun probeDac(
        fd: Int,
        acIface: Int = 0,
        streamingIface: Int = 1,
        isUac2: Boolean = true,
        clockSourceId: Int = 9,
        featureUnitId: Int = 0,
        fuHasVolume: Boolean = false,
        fuHasMasterVolume: Boolean = false,
        fuHasMute: Boolean = false,
    ): String?
    fun getNativeLog(): String?
}

/**
 * Manages USB stream format state, atomic transitions, and readback verification.
 * Shared between production and test harnesses.
 */
class UsbStreamSwitchController(
    var sampleRate: Int,
    var targetAlt: Int,
    var bitDepth: Int,
    var channels: Int,
    var subslotSize: Int,
    var isDop: Boolean = false,
    val engineBridge: UsbEngineBridge
) {
    var nativeHandle: Long = 0L
    var isPrepared: Boolean = false

    @Volatile
    var confirmedRate: Int = sampleRate
        private set

    @Volatile
    var confirmedAlt: Int = targetAlt
        private set

    var currentVolumeIndex: Int = 10
    var maxVolumeIndex: Int = 25
    var dacMinDb: Float = -64.0f
    var dacMaxDb: Float = 0.0f
    var dacResDb: Float = 1.0f
    private var lastAppliedRawVolume: Int? = null

    fun invalidateVolumeCache() {
        lastAppliedRawVolume = null
    }

    fun applyHardwareVolume(force: Boolean = false): Boolean {
        val h = nativeHandle
        if (h == 0L) return false
        val db = UsbVolumeCurve.computeVolumeDb(currentVolumeIndex, maxVolumeIndex, dacMinDb, dacMaxDb, dacResDb)
        val minRaw: Short = (dacMinDb * 256f).toInt().toShort()
        val maxRaw: Short = (dacMaxDb * 256f).toInt().toShort()
        val resRaw: Short = (dacResDb * 256f).toInt().coerceAtLeast(1).toShort()
        val raw = UsbVolumeCurve.dbToRaw(db, minRaw, maxRaw, resRaw)
        if (!force && lastAppliedRawVolume == raw.toInt()) {
            return true
        }
        val written = engineBridge.setHardwareVolume(h, 0x05, 0, 0, raw.toInt())
        if (written >= 0) {
            lastAppliedRawVolume = raw.toInt()
            return true
        }
        return false
    }

    fun start(): Boolean {
        val h = nativeHandle
        if (h == 0L) return false
        applyHardwareVolume(force = true)
        return engineBridge.start(h) == 0
    }

    fun stop() {
        val h = nativeHandle
        if (h != 0L) {
            engineBridge.stop(h)
        }
    }

    fun toggleUsbExclusive(enabled: Boolean) {
        if (!enabled) {
            if (nativeHandle != 0L) {
                engineBridge.stop(nativeHandle)
                engineBridge.restoreDefaultVolume(nativeHandle)
                engineBridge.destroy(nativeHandle)
                nativeHandle = 0L
                isPrepared = false
                invalidateVolumeCache()
            }
        } else {
            val ok = reconfigure(sampleRate, targetAlt, bitDepth, channels, subslotSize, isDop)
            if (ok) {
                start()
            }
        }
    }

    fun reconfigure(
        newRate: Int,
        newAlt: Int,
        newBitDepth: Int,
        newChannels: Int,
        newSubslot: Int,
        newIsDop: Boolean = false
    ): Boolean {
        if (newRate == this.sampleRate &&
            newAlt == this.targetAlt &&
            newBitDepth == this.bitDepth &&
            newChannels == this.channels &&
            newSubslot == this.subslotSize &&
            newIsDop == this.isDop &&
            isPrepared &&
            nativeHandle != 0L
        ) {
            return true
        }

        var h = nativeHandle
        var reusedInPlace = false
        if (h != 0L) {
            val reconfRes = engineBridge.reconfigure(
                handle = h,
                endpoint = 1,
                streamingIface = 1,
                targetAlt = newAlt,
                sampleRate = newRate,
                bitDepth = newBitDepth,
                channels = newChannels,
                subslotSize = newSubslot,
                isHighSpeed = true,
                isDop = newIsDop,
                feedbackEp = 0
            )
            if (reconfRes == 0) {
                val readRate = engineBridge.getCurrentRate(h)
                val readAlt = engineBridge.getCurrentAlt(h)
                if ((readRate <= 0 || readRate == newRate) && (readAlt < 0 || readAlt == newAlt)) {
                    reusedInPlace = true
                    confirmedRate = if (readRate > 0) readRate else newRate
                    confirmedAlt = if (readAlt >= 0) readAlt else newAlt
                }
            }
            if (!reusedInPlace) {
                engineBridge.stop(h)
                engineBridge.destroy(h)
                nativeHandle = 0L
                isPrepared = false
                h = 0L
            }
        }

        if (!reusedInPlace) {
            h = engineBridge.create(
                fd = 42,
                endpoint = 1,
                streamingIface = 1,
                targetAlt = newAlt,
                isUac2 = true,
                clockSourceId = 1,
                controlIface = 0,
                sampleRate = newRate,
                bitDepth = newBitDepth,
                channels = newChannels,
                subslotSize = newSubslot,
                isHighSpeed = true,
                isDop = newIsDop,
                feedbackEp = 0
            )
            if (h == 0L) return false

            val readRate = engineBridge.getCurrentRate(h)
            val readAlt = engineBridge.getCurrentAlt(h)
            confirmedRate = if (readRate > 0) readRate else newRate
            confirmedAlt = if (readAlt >= 0) readAlt else newAlt

            if (readRate > 0 && readRate != newRate) {
                engineBridge.stop(h)
                engineBridge.destroy(h)
                nativeHandle = 0L
                isPrepared = false
                return false
            }
            if (readAlt >= 0 && readAlt != newAlt) {
                engineBridge.stop(h)
                engineBridge.destroy(h)
                nativeHandle = 0L
                isPrepared = false
                return false
            }
        }

        this.nativeHandle = h
        this.sampleRate = newRate
        this.targetAlt = newAlt
        this.bitDepth = newBitDepth
        this.channels = newChannels
        this.subslotSize = newSubslot
        this.isDop = newIsDop

        isPrepared = true
        invalidateVolumeCache()
        applyHardwareVolume(force = true)
        return true
    }
}

/**
 * Test implementation of [UsbEngineBridge] for verifying format switching,
 * readback validation, and error recovery.
 */
class FakeUsbEngineBridge : UsbEngineBridge {
    var nextHandle: Long = 100L
    val activeHandles = mutableSetOf<Long>()
    var currentRate: Int = 0
    var currentAlt: Int = 0
    var currentBitDepth: Int = 0
    var currentChannels: Int = 0
    var isDop: Boolean = false

    var simulatedReadbackRate: Int? = null
    var simulatedReadbackAlt: Int? = null

    var createdCount: Int = 0
    var reconfigureCount: Int = 0
    var failInPlaceReconfigure: Boolean = false
    var destroyedCount: Int = 0
    var startCount: Int = 0
    var stopCount: Int = 0
    var restoreDefaultVolumeCount: Int = 0
    var lastHardwareVolumeRaw: Int? = null
    val hardwareVolumeWrites = mutableListOf<Int>()
    val switchHistory = mutableListOf<Pair<Int, Int>>() // Pair(rate, alt)
    val callOrder = mutableListOf<String>()
    var probeCount: Int = 0

    override fun create(
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
    ): Long {
        val h = nextHandle++
        activeHandles.add(h)
        createdCount++
        callOrder.add("create:$h")
        currentRate = sampleRate
        currentAlt = targetAlt
        currentBitDepth = bitDepth
        currentChannels = channels
        this.isDop = isDop
        switchHistory.add(sampleRate to targetAlt)
        return h
    }

    override fun reconfigure(
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
    ): Int {
        callOrder.add("reconfigure:$handle")
        if (!activeHandles.contains(handle) || failInPlaceReconfigure) {
            return -1
        }
        reconfigureCount++
        currentRate = sampleRate
        currentAlt = targetAlt
        currentBitDepth = bitDepth
        currentChannels = channels
        this.isDop = isDop
        switchHistory.add(sampleRate to targetAlt)
        return 0
    }

    override fun destroy(handle: Long) {
        callOrder.add("destroy:$handle")
        if (activeHandles.remove(handle)) {
            destroyedCount++
        }
    }

    override fun start(handle: Long): Int {
        callOrder.add("start:$handle")
        startCount++
        return 0
    }

    override fun stop(handle: Long): Int {
        callOrder.add("stop:$handle")
        stopCount++
        return 0
    }

    override fun pause(handle: Long): Int = 0
    override fun resume(handle: Long): Int = 0
    override fun flush(handle: Long): Int = 0

    override fun write(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int {
        buffer.position(buffer.position() + length)
        return length
    }

    override fun getStats(handle: Long, outStats: LongArray) {
        if (outStats.size >= 9) {
            outStats[0] = 192000L // framesConsumed
            outStats[1] = 0L      // underruns
            outStats[2] = 0L      // urbErrors
            outStats[3] = 4096L   // ringFillBytes
            outStats[4] = 65536L  // ringCapacity
            outStats[5] = 1L      // isRunning
            outStats[6] = currentRate.toLong()
            outStats[7] = 0L      // isPaused
            outStats[8] = 0L      // isFlushing
        }
    }

    override fun playDebugTest(handle: Long, testType: Int, durationMs: Int): Int = 0

    override fun setHardwareVolume(
        handle: Long,
        featureUnitId: Int,
        controlIface: Int,
        channel: Int,
        volumeRaw: Int
    ): Int {
        lastHardwareVolumeRaw = volumeRaw
        hardwareVolumeWrites.add(volumeRaw)
        return volumeRaw
    }

    override fun setHardwareMute(
        handle: Long,
        featureUnitId: Int,
        controlIface: Int,
        channel: Int,
        mute: Boolean
    ): Int = if (mute) 1 else 0

    override fun getCurrentRate(handle: Long): Int {
        return simulatedReadbackRate ?: currentRate
    }

    override fun getCurrentAlt(handle: Long): Int {
        return simulatedReadbackAlt ?: currentAlt
    }

    override fun restoreDefaultVolume(handle: Long): Int {
        callOrder.add("restoreDefaultVolume:$handle")
        check(activeHandles.contains(handle)) { "restoreDefaultVolume called after handle $handle was destroyed!" }
        restoreDefaultVolumeCount++
        lastHardwareVolumeRaw = 0
        return 0
    }

    override fun probeDac(
        fd: Int,
        acIface: Int,
        streamingIface: Int,
        isUac2: Boolean,
        clockSourceId: Int,
        featureUnitId: Int,
        fuHasVolume: Boolean,
        fuHasMasterVolume: Boolean,
        fuHasMute: Boolean,
    ): String? {
        probeCount++
        callOrder.add("probeDac:$fd")
        return null
    }
    override fun getNativeLog(): String? = "FakeUsbEngineBridge log"
}
