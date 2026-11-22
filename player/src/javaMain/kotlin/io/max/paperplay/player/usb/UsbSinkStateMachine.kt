package io.max.paperplay.player.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

interface UsbAudioSinkBridge {
    fun prepare(): Boolean
    fun start(): Boolean
    fun pause()
    fun resume() {}
    fun flush()
    fun stopAndRelease()
    fun write(buffer: ByteBuffer, offset: Int, length: Int): Int
    fun getStats(): UsbStreamStats?
    fun switchRate(newRate: Int): Boolean
    fun reconfigure(altSetting: UsbAudioAltSetting, rate: Int, isDop: Boolean): Boolean = switchRate(rate)
    val isPrepared: Boolean
    val isRunning: Boolean
    val isPaused: Boolean get() = false
    val confirmedRate: Int get() = 0
    val confirmedAlt: Int get() = -1
}

enum class UsbSinkState {
    UNCONFIGURED,
    CONFIGURED,
    PLAYING,
    PAUSED,
    ENDED,
    FALLBACK
}

class UsbConfigurationException(message: String) : Exception(message)

/**
 * State machine managing the lifecycle, format negotiation, zero-allocation repacking,
 * gapless playback, watchdog timeout, and fallback for USB Audio exclusive mode.
 */
class UsbSinkStateMachine(
    private val bridgeFactory: (altSetting: UsbAudioAltSetting, rate: Int, isDop: Boolean) -> UsbAudioSinkBridge?,
    private val onFallbackTriggered: (reason: String) -> Unit = {}
) {
    companion object {
        const val CURRENT_POSITION_NOT_SET: Long = Long.MIN_VALUE
    }
    var state: UsbSinkState = UsbSinkState.UNCONFIGURED
        private set

    var fallbackReason: String? = null
        private set

    var currentAltSetting: UsbAudioAltSetting? = null
        private set
    var currentRate: Int = 0
        private set
    var currentBitDepth: Int = 0
        private set
    var currentChannels: Int = 0
        private set
    var currentSubslotSize: Int = 0
        private set
    var isFloatInput: Boolean = false
        private set
    var isDop: Boolean = false
        private set

    var activeBridge: UsbAudioSinkBridge? = null
        private set

    val confirmedRate: Int get() = activeBridge?.confirmedRate ?: 0
    val confirmedAlt: Int get() = activeBridge?.confirmedAlt ?: -1

    var isPlayRequested: Boolean = false
        private set
    private var playStartTimeMs: Long = 0L
    private var isEndOfStream: Boolean = false

    var hasHardwareVolume: Boolean = false
    var softwareVolume: Float = 1.0f
        private set

    private var hasBaseline: Boolean = false
    private var baselineFrames: Long = 0L
    private var baselineMediaTimeUs: Long = 0L
    private var lastPresentationTimeUs: Long = 0L

    var waitingForFirstBufferAfterReset: Boolean = false
        private set

    // Partial frame buffer for carrying incomplete frames across buffers
    private val partialFrameBuffer = ByteArray(32)
    private var partialFrameLen: Int = 0

    // Reusable preallocated direct buffer for zero-allocation repacking / float conversion
    private var repackBuffer: ByteBuffer? = null
    private val singleFrameBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)

    @Volatile
    var onWriteHook: ((ByteBuffer, Int, Int) -> Unit)? = null

    private fun writeToBridge(bridge: UsbAudioSinkBridge, buffer: ByteBuffer, offset: Int, length: Int): Int {
        val written = bridge.write(buffer, offset, length)
        if (written > 0) {
            onWriteHook?.invoke(buffer, offset, written)
        }
        return written
    }

    val isActive: Boolean
        get() = state != UsbSinkState.UNCONFIGURED && state != UsbSinkState.FALLBACK

    /**
     * Configures the sink with requested format and available USB alternate settings.
     * Prepares the session immediately (claims interface, sets alt 0, programs clock rate,
     * verifies GET_CUR, selects target alt, allocates ring buffer) without starting URBs.
     */
    fun configure(
        formatRate: Int,
        formatBitDepth: Int,
        formatChannels: Int,
        availableAltSettings: List<UsbAudioAltSetting>,
        isDop: Boolean = false,
        isFloat: Boolean = false,
        sampleMimeType: String? = null
    ): Boolean {
        if (sampleMimeType != null && sampleMimeType != "audio/raw") {
            val reason = "Cannot stream non-raw format ($sampleMimeType) directly to USB DAC"
            triggerFallback(reason)
            throw UsbConfigurationException(reason)
        }

        // 1. If currently active and the format matches exactly: gapless track transition!
        val currentAlt = currentAltSetting
        val bridge = activeBridge
        if (isActive && currentAlt != null && bridge != null && bridge.isPrepared &&
            currentRate == formatRate &&
            currentBitDepth == formatBitDepth &&
            currentChannels == formatChannels &&
            isFloatInput == isFloat
        ) {
            isEndOfStream = false
            waitingForFirstBufferAfterReset = false
            if (isPlayRequested && bridge.isPaused) {
                bridge.resume()
                state = UsbSinkState.PLAYING
            } else {
                state = if (bridge.isRunning && isPlayRequested && !bridge.isPaused) UsbSinkState.PLAYING else UsbSinkState.CONFIGURED
            }
            return true
        }

        // 2. Select best matching alt setting
        var negotiatedBitDepth = formatBitDepth
        var result = UsbFormatNegotiator.pickBestFormat(
            candidates = availableAltSettings,
            sourceRate = formatRate,
            sourceBitDepth = negotiatedBitDepth,
            sourceChannels = formatChannels
        )
        if (result is FormatSelectionResult.Failure && isFloat && negotiatedBitDepth > 24) {
            // Float input: if 32-bit selection failed, attempt 24-bit selection
            val retry = UsbFormatNegotiator.pickBestFormat(
                candidates = availableAltSettings,
                sourceRate = formatRate,
                sourceBitDepth = 24,
                sourceChannels = formatChannels
            )
            if (retry is FormatSelectionResult.Success) {
                negotiatedBitDepth = 24
                result = retry
            }
        }

        val candidate = when (result) {
            is FormatSelectionResult.Success -> result.candidate
            is FormatSelectionResult.Failure -> {
                val reason = "Unsupported format ($formatRate Hz, $formatBitDepth-bit, $formatChannels ch): ${result.reason}"
                triggerFallback(reason)
                throw UsbConfigurationException("USB DAC cannot support format: ${result.reason}")
            }
        }

        // 3. Try to reconfigure existing bridge if one is already active and prepared (keeps USB session alive across tracks)
        val existingBridge = activeBridge
        if (isActive && existingBridge != null && existingBridge.isPrepared) {
            val reconfigured = try {
                existingBridge.reconfigure(candidate, formatRate, isDop)
            } catch (_: Throwable) {
                false
            }
            if (reconfigured) {
                currentAltSetting = candidate
                currentRate = formatRate
                currentBitDepth = negotiatedBitDepth
                currentChannels = formatChannels
                currentSubslotSize = candidate.subslotSize
                isFloatInput = isFloat
                this.isDop = isDop
                isPlayRequested = false
                playStartTimeMs = 0L
                isEndOfStream = false
                partialFrameLen = 0
                hasBaseline = false
                baselineFrames = 0L
                baselineMediaTimeUs = 0L
                lastPresentationTimeUs = 0L

                // Allocate 64 KB reusable direct buffer for repacking / float conversion
                if (isFloat || (formatBitDepth == 24 && candidate.subslotSize == 4)) {
                    if (repackBuffer == null || repackBuffer?.capacity() != 65536) {
                        repackBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
                    } else {
                        repackBuffer?.clear()
                    }
                } else {
                    repackBuffer = null
                }

                state = if (existingBridge.isRunning && isPlayRequested && !existingBridge.isPaused) {
                    UsbSinkState.PLAYING
                } else if (existingBridge.isPaused) {
                    UsbSinkState.PAUSED
                } else {
                    UsbSinkState.CONFIGURED
                }
                return true
            }
        }

        // 4. Clean up previous bridge if format changed or reconfigure was not possible
        if (activeBridge != null) {
            try {
                activeBridge?.stopAndRelease()
            } catch (_: Throwable) {}
            activeBridge = null
        }

        // 5. Create and prepare bridge session now
        val newBridge = try {
            bridgeFactory(candidate, formatRate, isDop)
        } catch (t: Throwable) {
            val reason = "Failed to instantiate USB bridge: ${t.message}"
            triggerFallback(reason, t)
            throw UsbConfigurationException(reason)
        }
        if (newBridge == null) {
            val reason = "Failed to instantiate USB bridge for candidate alt ${candidate.altSetting}"
            triggerFallback(reason)
            throw UsbConfigurationException(reason)
        }

        val prepared = try {
            newBridge.prepare()
        } catch (t: Throwable) {
            val reason = "Failed to prepare USB audio session: ${t.message}"
            triggerFallback(reason, t)
            throw UsbConfigurationException(reason)
        }
        if (!prepared) {
            val reason = "Failed to prepare USB audio session for alt ${candidate.altSetting} at $formatRate Hz"
            triggerFallback(reason)
            throw UsbConfigurationException(reason)
        }

        activeBridge = newBridge
        currentAltSetting = candidate
        currentRate = formatRate
        currentBitDepth = negotiatedBitDepth
        currentChannels = formatChannels
        currentSubslotSize = candidate.subslotSize
        isFloatInput = isFloat
        this.isDop = isDop
        isPlayRequested = false
        playStartTimeMs = 0L
        isEndOfStream = false
        partialFrameLen = 0
        hasBaseline = false
        baselineFrames = 0L
        baselineMediaTimeUs = 0L
        lastPresentationTimeUs = 0L

        // Allocate 64 KB reusable direct buffer for repacking / float conversion
        if (isFloat || (formatBitDepth == 24 && candidate.subslotSize == 4)) {
            if (repackBuffer == null || repackBuffer?.capacity() != 65536) {
                repackBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
            } else {
                repackBuffer?.clear()
            }
        } else {
            repackBuffer = null
        }

        state = UsbSinkState.CONFIGURED
        return true
    }

    /**
     * Accepts data into the ring even while paused, until the ring is full.
     * Returns true only when the buffer is fully consumed; otherwise advances buffer position
     * by what was copied and returns false. Carries partial frames across buffers without blocking.
     */
    fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        if (!isActive) return false
        val bridge = activeBridge ?: return false

        waitingForFirstBufferAfterReset = false
        if (isPlayRequested && bridge.isPaused) {
            bridge.resume()
            state = UsbSinkState.PLAYING
        }

        checkWatchdog()
        if (state == UsbSinkState.FALLBACK) return false

        val stats = bridge.getStats()
        if (stats != null && stats.urbErrors > 0) {
            triggerFallback("USB URB error detected (${stats.urbErrors} errors)")
            return false
        }

        if (!hasBaseline) {
            hasBaseline = true
            baselineMediaTimeUs = presentationTimeUs
            baselineFrames = stats?.framesConsumed ?: 0L
        }
        lastPresentationTimeUs = presentationTimeUs

        val sourceBytesPerSample = if (isFloatInput) 4 else (currentBitDepth / 8).coerceAtLeast(2)
        val sourceBytesPerFrame = currentChannels * sourceBytesPerSample
        val targetBytesPerFrame = currentChannels * currentSubslotSize

        val ringCap = (stats?.ringCapacity ?: 1048576L).toInt()
        val ringFill = (stats?.ringFillBytes ?: 0L).toInt()
        var ringSpace = (ringCap - ringFill).coerceAtLeast(0)

        // 1. Process carried partial frame if any
        if (partialFrameLen > 0) {
            val needed = sourceBytesPerFrame - partialFrameLen
            val available = buffer.remaining()
            val toTake = minOf(needed, available)
            buffer.get(partialFrameBuffer, partialFrameLen, toTake)
            partialFrameLen += toTake

            if (partialFrameLen == sourceBytesPerFrame) {
                if (ringSpace >= targetBytesPerFrame) {
                    writeSingleFrame(partialFrameBuffer, targetBytesPerFrame, bridge)
                    ringSpace = (ringSpace - targetBytesPerFrame).coerceAtLeast(0)
                    partialFrameLen = 0
                } else {
                    return false // Ring full, retry later
                }
            } else {
                return true // Whole buffer consumed into partialFrameBuffer
            }
        }

        // 2. Ingest complete frames from buffer into ring
        while (buffer.remaining() >= sourceBytesPerFrame && ringSpace >= targetBytesPerFrame) {
            val framesInBuffer = buffer.remaining() / sourceBytesPerFrame
            val framesCanFit = ringSpace / targetBytesPerFrame
            val framesToProcess = minOf(framesInBuffer, framesCanFit)
            if (framesToProcess <= 0) break

            if (isFloatInput) {
                val rb = repackBuffer ?: ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN).also { repackBuffer = it }
                rb.clear()
                val chunkMaxFrames = (rb.capacity() / targetBytesPerFrame).coerceAtLeast(1)
                val chunkFrames = minOf(framesToProcess, chunkMaxFrames)

                for (f in 0 until chunkFrames) {
                    for (c in 0 until currentChannels) {
                        val rawFl = buffer.getFloat()
                        val fl = if (!hasHardwareVolume) rawFl * softwareVolume else rawFl
                        when (currentSubslotSize) {
                            2 -> {
                                val s16 = (fl.toDouble() * 32768.0).roundToLong().coerceIn(-32768L, 32767L).toShort()
                                rb.putShort(s16)
                            }
                            3 -> {
                                val s24 = (fl.toDouble() * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                                rb.put((s24 and 0xFF).toByte())
                                rb.put(((s24 shr 8) and 0xFF).toByte())
                                rb.put(((s24 shr 16) and 0xFF).toByte())
                            }
                            else -> {
                                if (currentBitDepth == 32) {
                                    val s32 = (fl.toDouble() * 2147483648.0).roundToLong().coerceIn(-2147483648L, 2147483647L).toInt()
                                    rb.putInt(s32)
                                } else {
                                    val s24 = (fl.toDouble() * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                                    rb.put((s24 and 0xFF).toByte())
                                    rb.put(((s24 shr 8) and 0xFF).toByte())
                                    rb.put(((s24 shr 16) and 0xFF).toByte())
                                    rb.put(0.toByte())
                                }
                            }
                        }
                    }
                }
                rb.flip()
                val targetBytes = chunkFrames * targetBytesPerFrame
                val written = writeToBridge(bridge, rb, 0, targetBytes)
                if (written > 0) {
                    ringSpace = (ringSpace - written).coerceAtLeast(0)
                }
                if (written < targetBytes) {
                    break
                }
            } else if (currentBitDepth == 24 && currentSubslotSize == 4) {
                // 24-bit input repacking into 4-byte subslots
                val rb = repackBuffer ?: ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN).also { repackBuffer = it }
                rb.clear()
                val chunkMaxFrames = (rb.capacity() / targetBytesPerFrame).coerceAtLeast(1)
                val chunkFrames = minOf(framesToProcess, chunkMaxFrames)

                for (f in 0 until chunkFrames) {
                    for (c in 0 until currentChannels) {
                        val b0 = buffer.get()
                        val b1 = buffer.get()
                        val b2 = buffer.get()
                        if (!hasHardwareVolume && softwareVolume < 0.999f) {
                            val raw24 = ((b0.toInt() and 0xFF) or ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() shl 16)).let {
                                if (it and 0x800000 != 0) it or -0x1000000 else it
                            }
                            val s24 = (raw24.toDouble() * softwareVolume).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                            rb.put((s24 and 0xFF).toByte())
                            rb.put(((s24 shr 8) and 0xFF).toByte())
                            rb.put(((s24 shr 16) and 0xFF).toByte())
                            rb.put(0.toByte())
                        } else {
                            rb.put(b0)
                            rb.put(b1)
                            rb.put(b2)
                            rb.put(0.toByte())
                        }
                    }
                }
                rb.flip()
                val targetBytes = chunkFrames * targetBytesPerFrame
                val written = writeToBridge(bridge, rb, 0, targetBytes)
                if (written > 0) {
                    ringSpace = (ringSpace - written).coerceAtLeast(0)
                }
                if (written < targetBytes) {
                    break
                }
            } else {
                if (!hasHardwareVolume && softwareVolume < 0.999f) {
                    val rb = repackBuffer ?: ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN).also { repackBuffer = it }
                    rb.clear()
                    val chunkMaxFrames = (rb.capacity() / targetBytesPerFrame).coerceAtLeast(1)
                    val chunkFrames = minOf(framesToProcess, chunkMaxFrames)
                    for (f in 0 until chunkFrames) {
                        for (c in 0 until currentChannels) {
                            when (currentSubslotSize) {
                                2 -> {
                                    val s16 = buffer.getShort()
                                    val scaled = (s16.toDouble() * softwareVolume).roundToLong().coerceIn(-32768L, 32767L).toShort()
                                    rb.putShort(scaled)
                                }
                                3 -> {
                                    val b0 = buffer.get()
                                    val b1 = buffer.get()
                                    val b2 = buffer.get()
                                    val raw24 = ((b0.toInt() and 0xFF) or ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() shl 16)).let {
                                        if (it and 0x800000 != 0) it or -0x1000000 else it
                                    }
                                    val s24 = (raw24.toDouble() * softwareVolume).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                                    rb.put((s24 and 0xFF).toByte())
                                    rb.put(((s24 shr 8) and 0xFF).toByte())
                                    rb.put(((s24 shr 16) and 0xFF).toByte())
                                }
                                else -> {
                                    val s32 = buffer.getInt()
                                    val scaled = (s32.toDouble() * softwareVolume).roundToLong().coerceIn(-2147483648L, 2147483647L).toInt()
                                    rb.putInt(scaled)
                                }
                            }
                        }
                    }
                    rb.flip()
                    val targetBytes = chunkFrames * targetBytesPerFrame
                    val written = writeToBridge(bridge, rb, 0, targetBytes)
                    if (written > 0) {
                        ringSpace = (ringSpace - written).coerceAtLeast(0)
                    }
                    if (written < targetBytes) {
                        break
                    }
                } else {
                    // Direct bit-perfect passthrough
                    val bytesToWrite = framesToProcess * sourceBytesPerFrame
                    val written = writeToBridge(bridge, buffer, buffer.position(), bytesToWrite)
                    if (written > 0) {
                        buffer.position(buffer.position() + written)
                        ringSpace = (ringSpace - written).coerceAtLeast(0)
                    }
                    break
                }
            }
        }

        // 3. Carry remaining partial frame bytes if any
        val leftover = buffer.remaining()
        if (leftover in 1 until sourceBytesPerFrame) {
            buffer.get(partialFrameBuffer, 0, leftover)
            partialFrameLen = leftover
        }

        // 4. Priming check: start or resume URB stream once ring has >= 20 ms
        val primingBytes = (20 * currentRate / 1000) * targetBytesPerFrame
        val currentFill = bridge.getStats()?.ringFillBytes ?: 0L
        if (bridge.isRunning) {
            if (isPlayRequested && (state == UsbSinkState.PAUSED || state == UsbSinkState.CONFIGURED || bridge.isPaused) &&
                (currentFill >= primingBytes || isEndOfStream)
            ) {
                bridge.resume()
                state = UsbSinkState.PLAYING
            }
        } else if (isPlayRequested) {
            if (currentFill >= primingBytes || isEndOfStream) {
                val started = try {
                    bridge.start()
                } catch (t: Throwable) {
                    triggerFallback("Failed to start USB audio bridge", t)
                    false
                }
                if (started) {
                    state = UsbSinkState.PLAYING
                } else if (state != UsbSinkState.FALLBACK) {
                    triggerFallback("Failed to start USB audio bridge")
                }
            }
        }

        return !buffer.hasRemaining()
    }

    private fun writeSingleFrame(frameBytes: ByteArray, targetBytesPerFrame: Int, bridge: UsbAudioSinkBridge) {
        val fb = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        singleFrameBuffer.clear()
        if (isFloatInput) {
            for (c in 0 until currentChannels) {
                val rawFl = fb.getFloat()
                val fl = if (!hasHardwareVolume) rawFl * softwareVolume else rawFl
                when (currentSubslotSize) {
                    2 -> {
                        val s16 = (fl.toDouble() * 32768.0).roundToLong().coerceIn(-32768L, 32767L).toShort()
                        singleFrameBuffer.putShort(s16)
                    }
                    3 -> {
                        val s24 = (fl.toDouble() * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                        singleFrameBuffer.put((s24 and 0xFF).toByte())
                        singleFrameBuffer.put(((s24 shr 8) and 0xFF).toByte())
                        singleFrameBuffer.put(((s24 shr 16) and 0xFF).toByte())
                    }
                    else -> {
                        if (currentBitDepth == 32) {
                            val s32 = (fl.toDouble() * 2147483648.0).roundToLong().coerceIn(-2147483648L, 2147483647L).toInt()
                            singleFrameBuffer.putInt(s32)
                        } else {
                            val s24 = (fl.toDouble() * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                            singleFrameBuffer.put((s24 and 0xFF).toByte())
                            singleFrameBuffer.put(((s24 shr 8) and 0xFF).toByte())
                            singleFrameBuffer.put(((s24 shr 16) and 0xFF).toByte())
                            singleFrameBuffer.put(0.toByte())
                        }
                    }
                }
            }
        } else if (currentBitDepth == 24 && currentSubslotSize == 4) {
            for (c in 0 until currentChannels) {
                val b0 = fb.get()
                val b1 = fb.get()
                val b2 = fb.get()
                if (!hasHardwareVolume && softwareVolume < 0.999f) {
                    val raw24 = ((b0.toInt() and 0xFF) or ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() shl 16)).let {
                        if (it and 0x800000 != 0) it or -0x1000000 else it
                    }
                    val s24 = (raw24.toDouble() * softwareVolume).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                    singleFrameBuffer.put((s24 and 0xFF).toByte())
                    singleFrameBuffer.put(((s24 shr 8) and 0xFF).toByte())
                    singleFrameBuffer.put(((s24 shr 16) and 0xFF).toByte())
                    singleFrameBuffer.put(0.toByte())
                } else {
                    singleFrameBuffer.put(b0)
                    singleFrameBuffer.put(b1)
                    singleFrameBuffer.put(b2)
                    singleFrameBuffer.put(0.toByte())
                }
            }
        } else {
            if (!hasHardwareVolume && softwareVolume < 0.999f) {
                for (c in 0 until currentChannels) {
                    when (currentSubslotSize) {
                        2 -> {
                            val s16 = fb.getShort()
                            val scaled = (s16.toDouble() * softwareVolume).roundToLong().coerceIn(-32768L, 32767L).toShort()
                            singleFrameBuffer.putShort(scaled)
                        }
                        3 -> {
                            val b0 = fb.get()
                            val b1 = fb.get()
                            val b2 = fb.get()
                            val raw24 = ((b0.toInt() and 0xFF) or ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() shl 16)).let {
                                if (it and 0x800000 != 0) it or -0x1000000 else it
                            }
                            val s24 = (raw24.toDouble() * softwareVolume).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                            singleFrameBuffer.put((s24 and 0xFF).toByte())
                            singleFrameBuffer.put(((s24 shr 8) and 0xFF).toByte())
                            singleFrameBuffer.put(((s24 shr 16) and 0xFF).toByte())
                        }
                        else -> {
                            val s32 = fb.getInt()
                            val scaled = (s32.toDouble() * softwareVolume).roundToLong().coerceIn(-2147483648L, 2147483647L).toInt()
                            singleFrameBuffer.putInt(scaled)
                        }
                    }
                }
            } else {
                singleFrameBuffer.put(frameBytes, 0, targetBytesPerFrame)
            }
        }
        singleFrameBuffer.flip()
        writeToBridge(bridge, singleFrameBuffer, 0, targetBytesPerFrame)
    }

    /**
     * Hardware clock position strictly calculated from frames transmitted to DAC by completed URBs.
     */
    fun getCurrentPositionUs(sourceEnded: Boolean = false): Long {
        if (!isActive) return 0L
        if (waitingForFirstBufferAfterReset) {
            return CURRENT_POSITION_NOT_SET
        }
        checkWatchdog()

        val stats = activeBridge?.getStats() ?: return lastPresentationTimeUs
        val consumed = stats.framesConsumed
        val delta = if (hasBaseline) (consumed - baselineFrames).coerceAtLeast(0L) else 0L
        val rate = currentRate.takeIf { it > 0 } ?: 44100
        val posUs = baselineMediaTimeUs + (delta * 1_000_000L) / rate

        return if (sourceEnded && !hasPendingData()) {
            posUs.coerceAtLeast(lastPresentationTimeUs)
        } else {
            posUs
        }
    }

    fun play() {
        if (!isActive) return
        isPlayRequested = true
        playStartTimeMs = System.currentTimeMillis()

        val bridge = activeBridge ?: return
        if (bridge.isRunning) {
            bridge.resume()
            state = UsbSinkState.PLAYING
            return
        }
        val targetBytesPerFrame = currentChannels * currentSubslotSize
        val primingBytes = (20 * currentRate / 1000) * targetBytesPerFrame
        val fill = bridge.getStats()?.ringFillBytes ?: 0L

        if (fill >= primingBytes || isEndOfStream) {
            val started = try {
                bridge.start()
            } catch (t: Throwable) {
                triggerFallback("Failed to start USB audio bridge", t)
                false
            }
            if (started) {
                state = UsbSinkState.PLAYING
            } else if (state != UsbSinkState.FALLBACK) {
                triggerFallback("Failed to start USB audio bridge")
            }
        }
    }

    fun pause() {
        if (!isActive) return
        isPlayRequested = false
        state = UsbSinkState.PAUSED
        activeBridge?.pause()
    }

    fun flush(preservePlayRequested: Boolean = false) {
        partialFrameLen = 0
        repackBuffer?.clear()
        activeBridge?.flush()

        waitingForFirstBufferAfterReset = true
        val stats = activeBridge?.getStats()
        hasBaseline = false
        baselineFrames = stats?.framesConsumed ?: 0L
        baselineMediaTimeUs = 0L
        if (!preservePlayRequested) {
            isPlayRequested = false
            playStartTimeMs = 0L
        }
        isEndOfStream = false
        if (isActive) {
            state = UsbSinkState.CONFIGURED
        }
    }

    fun reset(preservePlayRequested: Boolean = false) {
        partialFrameLen = 0
        repackBuffer?.clear()
        activeBridge?.flush()
        activeBridge?.pause()

        waitingForFirstBufferAfterReset = true
        hasBaseline = false
        baselineFrames = 0L
        baselineMediaTimeUs = 0L
        if (preservePlayRequested) {
            isPlayRequested = true
        } else {
            isPlayRequested = false
            playStartTimeMs = 0L
        }
        isEndOfStream = false
        if (activeBridge != null) {
            state = UsbSinkState.CONFIGURED
        } else {
            state = UsbSinkState.UNCONFIGURED
        }
    }

    fun release() {
        activeBridge?.stopAndRelease()
        activeBridge = null
        currentAltSetting = null
        fallbackReason = null
        repackBuffer = null
        partialFrameLen = 0
        waitingForFirstBufferAfterReset = false
        hasBaseline = false
        baselineFrames = 0L
        baselineMediaTimeUs = 0L
        isPlayRequested = false
        playStartTimeMs = 0L
        isEndOfStream = false
        state = UsbSinkState.UNCONFIGURED
    }

    fun playToEndOfStream() {
        isEndOfStream = true
        if (!hasPendingData()) {
            state = UsbSinkState.ENDED
        }
    }

    fun isEnded(): Boolean {
        return isEndOfStream && !hasPendingData()
    }

    fun hasPendingData(): Boolean {
        if (waitingForFirstBufferAfterReset) return false
        val fill = (activeBridge?.getStats()?.ringFillBytes ?: 0L) > 0L
        return fill || partialFrameLen > 0
    }

    fun checkWatchdog() {
        if (isPlayRequested && state != UsbSinkState.FALLBACK) {
            val stats = activeBridge?.getStats()
            val consumed = stats?.framesConsumed ?: 0L
            if (consumed == 0L && playStartTimeMs > 0L) {
                val elapsed = System.currentTimeMillis() - playStartTimeMs
                if (elapsed >= 2000L) {
                    val reason = "Watchdog timeout: no frames consumed 2s after play()"
                    triggerFallback(reason)
                }
            }
        }
    }

    fun triggerFallback(reason: String, throwable: Throwable? = null) {
        val fullReason = if (throwable != null) {
            "$reason: ${throwable.message}\n${throwable.stackTraceToString()}"
        } else {
            reason
        }
        fallbackReason = fullReason
        state = UsbSinkState.FALLBACK
        UsbRingLog.record("UsbSinkStateMachine", "Fallback: $fullReason", UsbRingLog.CODE_SINK_FALLBACK)
        activeBridge?.stopAndRelease()
        activeBridge = null
        repackBuffer = null
        partialFrameLen = 0
        isPlayRequested = false
        playStartTimeMs = 0L
        onFallbackTriggered(fullReason)
    }

    fun setVolume(volume: Float) {
        softwareVolume = volume.coerceIn(0f, 1f)
    }
}
