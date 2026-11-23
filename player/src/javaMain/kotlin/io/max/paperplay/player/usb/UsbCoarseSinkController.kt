package io.max.paperplay.player.usb

import io.max.paperplay.player.BitPerfectEvaluator
import io.max.paperplay.player.MIME_AUDIO_DOP
import io.max.paperplay.player.ReconfigureEscalator
import io.max.paperplay.player.UnderrunWatch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Format conversion helper mapping Media3 decoded PCM/DoP into verified wire bytes (zero per-buffer allocs once warm). */
class UsbWireBufferConverter {
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)

    fun convert(
        src: ByteBuffer,
        encodingBits: Int,
        isFloat: Boolean,
        targetSubslotBytes: Int,
        targetBitDepth: Int = targetSubslotBytes * 8,
    ): ByteBuffer {
        if (!isFloat && encodingBits == targetBitDepth && encodingBits == targetSubslotBytes * 8) {
            return src
        }
        val remaining = src.remaining()
        val samples = when {
            isFloat -> remaining / 4
            encodingBits == 16 -> remaining / 2
            encodingBits == 24 -> remaining / 3
            encodingBits == 32 -> remaining / 4
            else -> return src
        }
        val outLen = samples * targetSubslotBytes
        if (scratch.capacity() < outLen) {
            scratch = ByteBuffer.allocateDirect(outLen.coerceAtLeast(65536)).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        val ro = src.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            if (isFloat) {
                val f = ro.getFloat()
                if (targetSubslotBytes == 4 && targetBitDepth == 32) {
                    // MSB-aligned signed 32-bit PCM (S32_LE)
                    val s24 = BitPerfectEvaluator.float32ToInt24Pow2(f)
                    scratch.putInt(s24 shl 8)
                } else if (targetSubslotBytes == 4) {
                    // LSB-aligned 24-in-32 container
                    val s24 = BitPerfectEvaluator.float32ToInt24Pow2(f)
                    scratch.put((s24 and 0xFF).toByte())
                    scratch.put(((s24 ushr 8) and 0xFF).toByte())
                    scratch.put(((s24 ushr 16) and 0xFF).toByte())
                    scratch.put(0)
                } else if (targetSubslotBytes == 3) {
                    val s24 = BitPerfectEvaluator.float32ToInt24Pow2(f)
                    scratch.put((s24 and 0xFF).toByte())
                    scratch.put(((s24 ushr 8) and 0xFF).toByte())
                    scratch.put(((s24 ushr 16) and 0xFF).toByte())
                } else {
                    val s16 = kotlin.math.round(f * 32768.0).coerceIn(-32768.0, 32767.0).toInt()
                    scratch.putShort(s16.toShort())
                }
            } else if (encodingBits == 16 && targetSubslotBytes == 4 && targetBitDepth == 32) {
                scratch.putInt(ro.getShort().toInt() shl 16)
            } else if (encodingBits == 16 && targetSubslotBytes == 4) {
                // Lossless zero-padded 16 -> 24-in-32 LSB container
                scratch.put(0)
                scratch.putShort(ro.getShort())
                scratch.put(0)
            } else if (encodingBits == 16 && targetSubslotBytes == 3) {
                scratch.put(0)
                scratch.putShort(ro.getShort())
            } else if (encodingBits == 24 && targetSubslotBytes == 4 && targetBitDepth == 32) {
                // MSB-aligned S32_LE from 24-bit source
                scratch.put(0)
                scratch.put(ro.get())
                scratch.put(ro.get())
                scratch.put(ro.get())
            } else if (encodingBits == 24 && targetSubslotBytes == 4) {
                scratch.put(ro.get())
                scratch.put(ro.get())
                scratch.put(ro.get())
                scratch.put(0)
            } else if (encodingBits == 32 && targetSubslotBytes == 4 && targetBitDepth == 24) {
                ro.get()
                scratch.put(ro.get())
                scratch.put(ro.get())
                scratch.put(ro.get())
                scratch.put(0)
            } else if (encodingBits == 32 && targetSubslotBytes == 3) {
                ro.get()
                scratch.put(ro.get())
                scratch.put(ro.get())
                scratch.put(ro.get())
            }
        }
        scratch.flip()
        return scratch
    }
}

/**
 * Pure JVM-testable sink session controller used by UnifiedUsbAudioSink.
 * Coordinates coarse JNI session open/reconfigure/volume/start/write/pause-grace/flush/close,
 * UnderrunWatch -> reconfigure, ReconfigureEscalator -> fallback, and DoP hard-stop guarantees.
 */
class UsbCoarseSinkController internal constructor(
    private val api: CoarseUsbSessionApi,
    private val clockMs: () -> Long,
    private val underrunWatch: UnderrunWatch = UnderrunWatch(clock = clockMs, needed = 3, windowMs = 1_000L, coolDownMs = 3_000L),
    private val reconfigureEscalator: ReconfigureEscalator = ReconfigureEscalator(clock = clockMs, maxReconfigures = 2, windowMs = 15_000L),
    private val onFallback: (String) -> Unit = {},
    private val onHardStop: (String) -> Unit = {},
) {
    constructor(
        api: CoarseUsbSessionApi,
        clockMs: () -> Long,
        onFallback: (String) -> Unit = {},
        onHardStop: (String) -> Unit = {},
    ) : this(
        api = api,
        clockMs = clockMs,
        underrunWatch = UnderrunWatch(clock = clockMs, needed = 3, windowMs = 1_000L, coolDownMs = 3_000L),
        reconfigureEscalator = ReconfigureEscalator(clock = clockMs, maxReconfigures = 2, windowMs = 15_000L),
        onFallback = onFallback,
        onHardStop = onHardStop,
    )
    @Volatile var handle: Long = 0L
        private set
    @Volatile var isStreaming: Boolean = false
        private set
    @Volatile var isPausedGrace: Boolean = false
        private set
    @Volatile var isFallback: Boolean = false
        private set
    @Volatile var isHardStopped: Boolean = false
        private set
    @Volatile var lastFailureReason: String? = null
        private set
    @Volatile var confirmedRateHz: Int = 0
        private set
    @Volatile var confirmedBitDepth: Int = 0
        private set
    @Volatile var confirmedAlt: Int = -1
        private set
    @Volatile var currentIsDop: Boolean = false
        private set
    @Volatile var lastVolumeReadbackRaw: Int = 0
        private set
    @Volatile var lastConfigureWasInPlace: Boolean = false
        private set
    @Volatile var lastSubslotBytes: Int = 4
        private set
    @Volatile var cushionEscalationRequests: Int = 0
        private set
    @Volatile var realFaultReconfigures: Int = 0
        private set
    @Volatile private var closing: Boolean = false
    @Volatile private var eosSignaled: Boolean = false

    private val lifecycleLock = Any()
    private val converter = UsbWireBufferConverter()
    private val statsScratch = LongArray(17)
    private var lastFlushAtMs: Long = Long.MIN_VALUE / 2
    private var lastObservedUnderruns: Long = 0L
    private var lastObservedUrbErrors: Long = 0L
    private var cushionEscalatedOnce: Boolean = false
    private var muteRampedDown: Boolean = false
    private var lastEndpoint: Int = 0
    private var lastStreamingIface: Int = 1
    private var lastChannels: Int = 2
    private var lastSubslotSize: Int = 4
    private var lastHighSpeed: Boolean = true
    private var lastFeedbackEp: Int = 0
    private var lastFuId: Int = 0
    private var lastControlIface: Int = 0
    private var lastVolumeChannels: List<Int> = listOf(0)
    private var lastVolRaw: Int = 0
    private var lastMute: Boolean = false

    companion object {
        const val SEEK_UNDERRUN_WINDOW_MS = 500L
        const val SINK_UNSUPPORTED = 0
        const val SINK_SUPPORTED_DIRECTLY = 2

        fun getFormatSupport(sampleMimeType: String?, pcmEncodingBits: Int, isRawOrFloat: Boolean, channelCount: Int, sampleRate: Int): Int {
            if (sampleRate <= 0 && sampleRate != -1) return SINK_UNSUPPORTED
            if (channelCount <= 0 && channelCount != -1) return SINK_UNSUPPORTED
            if (sampleMimeType == MIME_AUDIO_DOP) {
                return SINK_SUPPORTED_DIRECTLY
            }
            if (sampleMimeType != null && sampleMimeType != "audio/raw") {
                return SINK_UNSUPPORTED
            }
            return if (isRawOrFloat && pcmEncodingBits in setOf(16, 24, 32)) SINK_SUPPORTED_DIRECTLY else SINK_UNSUPPORTED
        }
    }

    fun openAndConfigure(
        fd: Int,
        controlIface: Int,
        streamingIface: Int,
        targetAlt: Int,
        endpoint: Int,
        isUac2: Boolean,
        clockSourceId: Int,
        hasHwVol: Boolean,
        fuId: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        subslotSize: Int,
        isHighSpeed: Boolean,
        isDop: Boolean,
        feedbackEp: Int,
        volumeRaw: Int,
        muted: Boolean,
        volumeChannels: List<Int> = listOf(0),
    ): Boolean = synchronized(lifecycleLock) {
        closing = false
        eosSignaled = false
        isFallback = false
        isHardStopped = false
        lastFailureReason = null
        currentIsDop = isDop

        if (!api.available) {
            return failOrHardStop("Native USB audio library unavailable")
        }

        return try {
            val wasAlreadyOpen = handle != 0L
            if (handle == 0L) {
                handle = api.open(fd, controlIface, streamingIface, isUac2, clockSourceId, hasHwVol, fuId)
                if (handle == 0L) {
                    return failOrHardStop("Failed to open USB DAC session")
                }
            }
            lastEndpoint = endpoint
            lastStreamingIface = streamingIface
            lastChannels = channels
            lastSubslotSize = subslotSize
            lastSubslotBytes = subslotSize
            lastHighSpeed = isHighSpeed
            lastFeedbackEp = feedbackEp
            lastFuId = fuId
            lastControlIface = controlIface
            lastVolumeChannels = volumeChannels.ifEmpty { listOf(0) }
            lastVolRaw = volumeRaw
            lastMute = muted

            val rcReconf = api.reconfigure(
                handle, endpoint, streamingIface, targetAlt, sampleRate, bitDepth,
                channels, subslotSize, isHighSpeed, isDop, feedbackEp,
            )
            if (rcReconf < 0) {
                closeUnlocked()
                return failOrHardStop("USB reconfigure failed (code=$rcReconf)")
            }
            api.flush(handle)

            if (hasHwVol && fuId > 0) {
                for (ch in lastVolumeChannels) {
                    val rcVol = api.applyVolume(handle, fuId, controlIface, ch, volumeRaw, muted)
                    if (rcVol < 0) {
                        closeUnlocked()
                        return failOrHardStop("Hardware volume verification failed (code=$rcVol)")
                    }
                    lastVolumeReadbackRaw = rcVol.toShort().toInt()
                }
            }

            val rcStart = api.start(handle)
            if (rcStart < 0) {
                closeUnlocked()
                return failOrHardStop("USB start failed (code=$rcStart)")
            }

            lastConfigureWasInPlace = wasAlreadyOpen
            confirmedRateHz = sampleRate
            confirmedBitDepth = bitDepth
            confirmedAlt = targetAlt
            isStreaming = true
            isPausedGrace = false
            underrunWatch.reset()
            reconfigureEscalator.reset()
            cushionEscalatedOnce = false
            val gotStats = api.getStats(handle, statsScratch) >= 0
            lastObservedUnderruns = if (gotStats) statsScratch[1] else 0L
            lastObservedUrbErrors = if (gotStats) statsScratch[2] else 0L
            true
        } catch (t: Throwable) {
            closeUnlocked()
            failOrHardStop(t.message ?: t::class.simpleName ?: "USB configuration error")
        }
    }

    fun applyLiveVolume(volumeRaw: Int, muted: Boolean): Boolean = synchronized(lifecycleLock) {
        val h = handle
        if (h == 0L || closing || isFallback || isHardStopped || lastFuId <= 0) return false
        var allOk = true
        for (ch in lastVolumeChannels) {
            val rc = api.applyVolume(h, lastFuId, lastControlIface, ch, volumeRaw, muted)
            if (rc < 0) {
                allOk = false
            } else {
                lastVolumeReadbackRaw = rc.toShort().toInt()
            }
        }
        lastVolRaw = volumeRaw
        lastMute = muted
        return allOk
    }

    fun readStats(outStats: LongArray): Boolean {
        val h = handle
        if (h == 0L || closing) return false
        return api.getStats(h, outStats) >= 0
    }

    fun writePcm(buffer: ByteBuffer, sourceBitDepth: Int, isFloat: Boolean): Boolean {
        val h = handle
        if (h == 0L || closing || isFallback || isHardStopped) return false
        return try {
            if (muteRampedDown && buffer.hasRemaining()) {
                api.setMuteRamp(h, false)
                muteRampedDown = false
            }
            val wireBuf = converter.convert(buffer, sourceBitDepth, isFloat, lastSubslotSize, confirmedBitDepth.takeIf { it > 0 } ?: (lastSubslotSize * 8))
            val beforePos = wireBuf.position()
            val len = wireBuf.remaining()
            if (len <= 0) return true

            val written = api.writePcm(h, wireBuf, beforePos, len)
            if (written < 0) {
                close()
                failOrHardStop("USB write error (code=$written)")
                return false
            }
            if (written > 0 && UsbPcmDumper.isDumping) {
                UsbPcmDumper.onBytesWritten(wireBuf, beforePos, written)
            }
            if (written == len) {
                buffer.position(buffer.limit())
            } else if (wireBuf === buffer) {
                buffer.position(beforePos + written)
            } else {
                // Consume proportional source input bytes when converter buffer accepted partial data
                val srcStep = if (isFloat || sourceBitDepth == 32) 4 else sourceBitDepth / 8
                val srcFrames = written / lastSubslotSize
                val advance = (srcFrames * srcStep).coerceAtMost(buffer.remaining())
                buffer.position(buffer.position() + advance)
            }

            checkUnderrunHealth(h)
            true
        } catch (t: Throwable) {
            close()
            failOrHardStop(t.message ?: "USB write exception")
            false
        }
    }

    private fun checkUnderrunHealth(h: Long) {
        if (api.getStats(h, statsScratch) < 0) return
        val totalUnderruns = statsScratch[1]
        val totalUrbErrors = statsScratch[2]
        val nativeRunning = statsScratch[5] != 0L
        val nativeRate = statsScratch[6].toInt()

        // 1. Pure ring underrun (no URB/device fault, no clock mismatch):
        //    Escalate URB cushion (16 -> 32) + resync idle slots. Never reconfigure or fall back.
        if (totalUnderruns > lastObservedUnderruns) {
            lastObservedUnderruns = totalUnderruns
            val now = clockMs()
            val nearSeek = (now - lastFlushAtMs) in 0L..SEEK_UNDERRUN_WINDOW_MS
            val shouldEscalate = !cushionEscalatedOnce || nearSeek || underrunWatch.hit()
            if (shouldEscalate) {
                cushionEscalatedOnce = true
                cushionEscalationRequests++
                api.escalateCushionAndResync(h)
            }
        }

        // 2. Real faults ONLY trigger reconfigure -> persistent-fault fallback:
        //    (a) URB/device error increase, (b) streaming thread unexpectedly stopped, or (c) clock rate mismatch.
        val urbErrorDelta = totalUrbErrors > lastObservedUrbErrors
        if (urbErrorDelta) {
            lastObservedUrbErrors = totalUrbErrors
        }
        val clockMismatch = nativeRate > 0 && confirmedRateHz > 0 && nativeRate != confirmedRateHz
        val unexpectedStop = isStreaming && !isPausedGrace && !nativeRunning && statsScratch[4] > 0L
        if (!urbErrorDelta && !clockMismatch && !unexpectedStop) return

        val faultDesc = when {
            urbErrorDelta -> "USB URB/device error (total=$totalUrbErrors)"
            clockMismatch -> "USB clock mismatch (actual=$nativeRate expected=$confirmedRateHz)"
            else -> "USB streaming thread stopped unexpectedly"
        }

        if (reconfigureEscalator.recordReconfigureAndShouldFallback()) {
            close()
            failOrHardStop("Persistent USB faults: $faultDesc")
            return
        }

        realFaultReconfigures++
        val rc = api.reconfigure(
            h, lastEndpoint, lastStreamingIface, confirmedAlt, confirmedRateHz, confirmedBitDepth,
            lastChannels, lastSubslotSize, lastHighSpeed, currentIsDop, lastFeedbackEp,
        )
        var volOk = true
        if (rc == 0 && lastFuId > 0) {
            for (ch in lastVolumeChannels) {
                if (api.applyVolume(h, lastFuId, lastControlIface, ch, lastVolRaw, lastMute) < 0) {
                    volOk = false
                }
            }
        }
        if (rc < 0 || !volOk || api.start(h) < 0) {
            close()
            failOrHardStop("USB reconfigure recovery failed (code=$rc)")
        } else {
            api.flush(h)
        }
    }

    fun onSeekOrFlush() {
        val h = handle
        if (h == 0L || closing) return
        lastFlushAtMs = clockMs()
        eosSignaled = false
        runCatching {
            api.setMuteRamp(h, true)
            muteRampedDown = true
            api.flush(h)
        }.onFailure {
            close()
            failOrHardStop("USB flush failed: ${it.message}")
        }
    }

    fun playToEndOfStream() {
        eosSignaled = true
    }

    fun hasPendingData(): Boolean {
        val h = handle
        if (h == 0L || closing) return false
        if (api.getStats(h, statsScratch) < 0) return false
        return statsScratch[3] > 0L
    }

    fun isEnded(): Boolean = eosSignaled && !hasPendingData()

    fun pauseGrace(): Boolean = synchronized(lifecycleLock) {
        val h = handle
        if (h == 0L || closing) return false
        return runCatching {
            val rc = api.pauseGrace(h)
            if (rc < 0) {
                closeUnlocked()
                failOrHardStop("USB pauseGrace failed (code=$rc)")
            } else {
                isStreaming = false
                isPausedGrace = true
                true
            }
        }.getOrElse {
            closeUnlocked()
            failOrHardStop("USB pause failed: ${it.message}")
        }
    }

    fun resumeGrace(): Boolean = synchronized(lifecycleLock) {
        val h = handle
        if (h == 0L || closing) return false
        return runCatching {
            val rc = api.start(h)
            if (rc < 0) {
                closeUnlocked()
                failOrHardStop("USB resume failed (code=$rc)")
            } else {
                isStreaming = true
                isPausedGrace = false
                true
            }
        }.getOrElse {
            closeUnlocked()
            failOrHardStop("USB resume exception: ${it.message}")
        }
    }

    fun close() = synchronized(lifecycleLock) {
        closeUnlocked()
    }

    fun closeIfHandleMatches(expectedHandle: Long) = synchronized(lifecycleLock) {
        if (expectedHandle != 0L && handle == expectedHandle) {
            closeUnlocked()
        }
    }

    private fun closeUnlocked() {
        closing = true
        eosSignaled = false
        val h = handle
        handle = 0L
        isStreaming = false
        isPausedGrace = false
        muteRampedDown = false
        if (h != 0L) {
            runCatching { api.close(h) }
        }
    }

    private fun failOrHardStop(reason: String): Boolean {
        lastFailureReason = reason
        if (currentIsDop) {
            isHardStopped = true
            onHardStop(reason)
        } else {
            isFallback = true
            onFallback(reason)
        }
        return false
    }
}
