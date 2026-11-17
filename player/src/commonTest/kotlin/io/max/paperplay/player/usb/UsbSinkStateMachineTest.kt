package io.max.paperplay.player.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeUsbAudioSinkBridge(
    var simulatedRate: Int,
    var simulatedSubslot: Int,
    var simulatedChannels: Int
) : UsbAudioSinkBridge {
    override var isPrepared: Boolean = false
    override var isRunning: Boolean = false
    override var isPaused: Boolean = false
    var isStopped: Boolean = false
    var writtenBytes = mutableListOf<Byte>()
    var framesConsumed: Long = 0L
    var underruns: Long = 0L
    var urbErrors: Long = 0L
    var ringFillBytes: Long = 0L
    var ringCapacity: Long = 1048576L
    var switchRateCalledWith: Int? = null
    var reconfigureCalledWith: Pair<UsbAudioAltSetting, Int>? = null
    var reconfigureReturnSuccess: Boolean = true

    override fun prepare(): Boolean {
        isPrepared = true
        return true
    }

    override fun start(): Boolean {
        isRunning = true
        return true
    }

    var resumeCalled: Boolean = false

    override fun resume() {
        resumeCalled = true
        isPaused = false
    }

    override fun pause() {
        isPaused = true
    }

    override fun flush() {
        ringFillBytes = 0L
        writtenBytes.clear()
    }

    override fun stopAndRelease() {
        isStopped = true
        isRunning = false
        isPrepared = false
    }

    override fun write(buffer: ByteBuffer, offset: Int, length: Int): Int {
        val src = buffer.duplicate()
        src.position(offset)
        val space = (ringCapacity - ringFillBytes).coerceAtLeast(0L).toInt()
        val toWrite = minOf(space, length)
        for (i in 0 until toWrite) {
            writtenBytes.add(src.get())
        }
        val bytesPerFrame = simulatedSubslot * simulatedChannels
        framesConsumed += toWrite / bytesPerFrame
        ringFillBytes += toWrite
        return toWrite
    }

    override fun getStats(): UsbStreamStats {
        return UsbStreamStats(
            framesConsumed = framesConsumed,
            underruns = underruns,
            urbErrors = urbErrors,
            ringFillBytes = ringFillBytes,
            ringCapacity = ringCapacity,
            isRunning = isRunning,
            sampleRate = simulatedRate
        )
    }

    override fun switchRate(newRate: Int): Boolean {
        switchRateCalledWith = newRate
        simulatedRate = newRate
        return true
    }

    override fun reconfigure(altSetting: UsbAudioAltSetting, rate: Int, isDop: Boolean): Boolean {
        reconfigureCalledWith = Pair(altSetting, rate)
        if (!reconfigureReturnSuccess) return false
        simulatedRate = rate
        simulatedSubslot = altSetting.subslotSize
        simulatedChannels = altSetting.channelCount
        return true
    }
}

class UsbSinkStateMachineTest {

    private fun sampleAltSettings(): List<UsbAudioAltSetting> {
        return listOf(
            UsbAudioAltSetting(
                interfaceNumber = 1,
                altSetting = 1,
                terminalLink = 1,
                formatTag = AudioFormatTag.PCM,
                bmFormats = 1L,
                channelCount = 2,
                subslotSize = 2,
                bitResolution = 16,
                sampleRates = listOf(44100, 48000),
                dataEndpoint = UsbEndpointDescriptor(
                    address = 0x01,
                    isOut = true,
                    syncType = EndpointSyncType.ASYNCHRONOUS,
                    usageType = EndpointUsageType.DATA,
                    maxPacketSize = 256,
                    interval = 1
                ),
                isDsdRawCapable = false,
                isDsdDoPCapable = false
            ),
            UsbAudioAltSetting(
                interfaceNumber = 1,
                altSetting = 2,
                terminalLink = 1,
                formatTag = AudioFormatTag.PCM,
                bmFormats = 1L,
                channelCount = 2,
                subslotSize = 4, // 24-bit in 32-bit slot
                bitResolution = 24,
                sampleRates = listOf(44100, 48000, 96000, 192000),
                dataEndpoint = UsbEndpointDescriptor(
                    address = 0x01,
                    isOut = true,
                    syncType = EndpointSyncType.ASYNCHRONOUS,
                    usageType = EndpointUsageType.DATA,
                    maxPacketSize = 512,
                    interval = 1
                ),
                isDsdRawCapable = false,
                isDsdDoPCapable = false
            ),
            UsbAudioAltSetting(
                interfaceNumber = 1,
                altSetting = 3,
                terminalLink = 1,
                formatTag = AudioFormatTag.PCM,
                bmFormats = 1L,
                channelCount = 2,
                subslotSize = 3, // 24-bit packed in 3-byte slot
                bitResolution = 24,
                sampleRates = listOf(44100, 48000, 96000),
                dataEndpoint = UsbEndpointDescriptor(
                    address = 0x01,
                    isOut = true,
                    syncType = EndpointSyncType.ASYNCHRONOUS,
                    usageType = EndpointUsageType.DATA,
                    maxPacketSize = 288,
                    interval = 1
                ),
                isDsdRawCapable = false,
                isDsdDoPCapable = false
            )
        )
    }

    @Test
    fun configurePreparesSessionWithoutStarting() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )

        // With both alt 2 (subslot 4) and alt 3 (subslot 3) present, negotiator prefers packed subslot (alt 3)
        val ok = sm.configure(96000, 24, 2, sampleAltSettings())
        assertTrue(ok)
        assertEquals(UsbSinkState.CONFIGURED, sm.state)
        assertEquals(3, sm.currentAltSetting?.altSetting)
        assertEquals(96000, sm.currentRate)

        // Must be prepared but NOT started
        assertTrue(createdBridge?.isPrepared == true)
        assertFalse(createdBridge?.isRunning == true)

        // When only alt 2 is available, alt 2 is selected
        val sm2 = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount)
            }
        )
        val ok2 = sm2.configure(96000, 24, 2, sampleAltSettings().filter { it.altSetting != 3 })
        assertTrue(ok2)
        assertEquals(2, sm2.currentAltSetting?.altSetting)
    }

    @Test
    fun configureThrowsWhenFormatIsUnsupported() {
        var fallbackReason: String? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { _, _, _ -> null },
            onFallbackTriggered = { fallbackReason = it }
        )

        assertFailsWith<UsbConfigurationException> {
            sm.configure(384000, 24, 2, sampleAltSettings())
        }

        assertEquals(UsbSinkState.FALLBACK, sm.state)
        assertTrue(fallbackReason?.contains("384000") == true)
    }

    @Test
    fun handleBufferAcceptsDataWhilePausedUntilFull() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also {
                    createdBridge = it
                    it.ringCapacity = 16 // small capacity for test
                }
            }
        )
        // 16-bit stereo = 4 bytes per frame
        sm.configure(48000, 16, 2, sampleAltSettings())
        // Currently configured / paused, NOT playing
        assertEquals(UsbSinkState.CONFIGURED, sm.state)

        // 12 bytes = 3 frames
        val data = ByteArray(12) { (it + 1).toByte() }
        val buf = ByteBuffer.wrap(data)
        val done = sm.handleBuffer(buf, 0L)
        assertTrue(done)
        assertEquals(12, createdBridge?.ringFillBytes)

        // Try writing 8 more bytes (only 4 bytes space left before 16 cap)
        val data2 = ByteArray(8) { (it + 1).toByte() }
        val buf2 = ByteBuffer.wrap(data2)
        val done2 = sm.handleBuffer(buf2, 100L)
        // Buffer was only partially consumed
        assertFalse(done2)
        assertEquals(4, buf2.position())
        assertEquals(16, createdBridge?.ringFillBytes)
    }

    @Test
    fun handleBufferAdvancesPositionAndCarriesPartialFrame() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )
        // 16-bit stereo = 4 bytes per frame
        sm.configure(48000, 16, 2, sampleAltSettings())

        // Feed 6 bytes (1 frame + 2 leftover bytes)
        val part1 = byteArrayOf(1, 2, 3, 4, 5, 6)
        val buf1 = ByteBuffer.wrap(part1)
        val done1 = sm.handleBuffer(buf1, 0L)
        // Whole buffer ingested (4 bytes written, 2 bytes in partial frame buffer)
        assertTrue(done1)
        assertEquals(6, buf1.position())
        assertEquals(4, createdBridge?.writtenBytes?.size)

        // Feed next 6 bytes (first 2 bytes complete the frame 5,6,7,8; next 4 bytes form frame 9,10,11,12)
        val part2 = byteArrayOf(7, 8, 9, 10, 11, 12)
        val buf2 = ByteBuffer.wrap(part2)
        val done2 = sm.handleBuffer(buf2, 50L)
        assertTrue(done2)
        assertEquals(6, buf2.position())
        assertEquals(12, createdBridge?.writtenBytes?.size)

        val expected = listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        assertEquals(expected, createdBridge?.writtenBytes?.toList())
    }

    @Test
    fun playStartsBridgeOncePrimedAndDrains() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )
        // 48000 Hz, 16-bit stereo: 4 bytes/frame. 20ms = 960 frames = 3840 bytes
        sm.configure(48000, 16, 2, sampleAltSettings())

        sm.play()
        assertTrue(sm.isPlayRequested)
        // Not yet primed, so not running
        assertFalse(createdBridge?.isRunning == true)

        // Feed 3840 bytes to satisfy 20ms priming threshold
        val primeData = ByteArray(3840)
        val buf = ByteBuffer.wrap(primeData)
        sm.handleBuffer(buf, 0L)

        // Now primed and running!
        assertTrue(createdBridge?.isRunning == true)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // Simulate URB consumer draining the frames
        val stats = createdBridge!!.getStats()
        assertTrue(stats.framesConsumed > 0)
        val posUs = sm.getCurrentPositionUs(sourceEnded = false)
        assertTrue(posUs >= 0)
    }

    @Test
    fun flushClearsRingAndResetsClock() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )
        sm.configure(48000, 16, 2, sampleAltSettings())

        val data = ByteArray(1000)
        sm.handleBuffer(ByteBuffer.wrap(data), 0L)
        assertTrue(createdBridge?.ringFillBytes ?: 0 > 0)

        sm.flush()
        assertEquals(0L, createdBridge?.ringFillBytes)
        assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs(sourceEnded = false))
        assertFalse(sm.isPlayRequested)
        assertEquals(UsbSinkState.CONFIGURED, sm.state)

        // First buffer after flush restores position reporting
        sm.handleBuffer(ByteBuffer.wrap(data), 0L)
        assertTrue(sm.getCurrentPositionUs(sourceEnded = false) >= 0L)
    }

    @Test
    fun pauseResumeMaintainsCadence() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )
        sm.configure(48000, 16, 2, sampleAltSettings())

        // Feed priming data and play
        sm.handleBuffer(ByteBuffer.wrap(ByteArray(4000)), 0L)
        sm.play()
        assertEquals(UsbSinkState.PLAYING, sm.state)

        sm.pause()
        assertEquals(UsbSinkState.PAUSED, sm.state)
        assertFalse(sm.isPlayRequested)

        sm.play()
        assertEquals(UsbSinkState.PLAYING, sm.state)
        assertTrue(sm.isPlayRequested)
    }

    @Test
    fun floatInputConversionTo24BitAnd32Bit() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )
        // Alt 2 has 24-bit in 32-bit slot (subslotSize = 4)
        val alt2Only = sampleAltSettings().filter { it.altSetting == 2 }
        sm.configure(96000, 24, 2, alt2Only, isFloat = true)
        sm.play()

        // 1 frame of float stereo: left = 0.5f, right = -1.0f
        val fb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fb.putFloat(0.5f)
        fb.putFloat(-1.0f)
        fb.flip()

        val done = sm.handleBuffer(fb, 0L)
        assertTrue(done)

        // 1 frame = 8 bytes (4 bytes left, 4 bytes right)
        val written = createdBridge?.writtenBytes ?: emptyList()
        assertEquals(8, written.size)

        // Right sample (-1.0f) should convert to -8388608: low byte 0x00, mid 0x00, high 0x80, slot pad 0x00
        val rightBytes = written.subList(4, 8)
        assertEquals(0x00.toByte(), rightBytes[0])
        assertEquals(0x00.toByte(), rightBytes[1])
        assertEquals(0x80.toByte(), rightBytes[2])
        assertEquals(0x00.toByte(), rightBytes[3])
    }

    @Test
    fun floatInputConversionToPacked24Bit() {
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )
        // Alt 3 has 24-bit packed in 3-byte slot (subslotSize = 3)
        val alt3Only = sampleAltSettings().filter { it.altSetting == 3 }
        sm.configure(96000, 24, 2, alt3Only, isFloat = true)
        sm.play()

        // 1 frame of float stereo: left = 0.5f, right = -1.0f
        val fb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fb.putFloat(0.5f)
        fb.putFloat(-1.0f)
        fb.flip()

        val done = sm.handleBuffer(fb, 0L)
        assertTrue(done)

        // 1 frame = 6 bytes (3 bytes left, 3 bytes right)
        val written = createdBridge?.writtenBytes ?: emptyList()
        assertEquals(6, written.size)

        // Right sample (-1.0f) should convert to -8388608: low byte 0x00, mid 0x00, high 0x80
        val rightBytes = written.subList(3, 6)
        assertEquals(0x00.toByte(), rightBytes[0])
        assertEquals(0x00.toByte(), rightBytes[1])
        assertEquals(0x80.toByte(), rightBytes[2])
    }

    @Test
    fun watchdogTimeoutTriggersFallbackWhenNoFramesConsumed() {
        var fallbackCalled = false
        var fallbackReasonMsg: String? = null
        val bridge = FakeUsbAudioSinkBridge(48000, 2, 2)
        val sm = UsbSinkStateMachine(
            bridgeFactory = { _, _, _ -> bridge },
            onFallbackTriggered = {
                fallbackCalled = true
                fallbackReasonMsg = it
            }
        )
        sm.configure(48000, 16, 2, sampleAltSettings())
        sm.play()

        // Frames consumed is 0
        bridge.framesConsumed = 0L

        // Simulate 2.5 seconds elapsed after play
        val field = UsbSinkStateMachine::class.java.getDeclaredField("playStartTimeMs")
        field.isAccessible = true
        field.set(sm, System.currentTimeMillis() - 2500L)

        sm.checkWatchdog()

        assertTrue(fallbackCalled)
        assertEquals(UsbSinkState.FALLBACK, sm.state)
        assertTrue(fallbackReasonMsg?.contains("Watchdog timeout") == true)
    }

    @Test
    fun encodedFlacFormatReportsUnsupportedInUsbMode() {
        // In USB exclusive mode with USB connected, encoded FLAC must report UNSUPPORTED (0)
        // so MediaCodecAudioRenderer is forced to decode to PCM rather than using bypass
        val support = UsbFormatSupport.getFormatSupport(
            isUsbExclusiveMode = true,
            isUsbConnected = true,
            sampleMimeType = "audio/flac",
            pcmEncoding = -1
        )
        assertEquals(UsbFormatSupport.SINK_FORMAT_UNSUPPORTED, support)
        assertFalse(UsbFormatSupport.isFormatSupported(true, true, "audio/flac", -1))

        // Same for other encoded formats (e.g. AAC, Vorbis, Opus)
        assertEquals(UsbFormatSupport.SINK_FORMAT_UNSUPPORTED, UsbFormatSupport.getFormatSupport(true, true, "audio/mp4a-latm", -1))
        assertEquals(UsbFormatSupport.SINK_FORMAT_UNSUPPORTED, UsbFormatSupport.getFormatSupport(true, true, "audio/ogg", -1))
    }

    @Test
    fun pcm24BitFormatPassesThroughInUsbMode() {
        // PCM 24-bit (enc=21), 16-bit (enc=2), 32-bit (enc=22), and float (enc=4) must report SUPPORTED_DIRECTLY (2)
        val pcm24Support = UsbFormatSupport.getFormatSupport(
            isUsbExclusiveMode = true,
            isUsbConnected = true,
            sampleMimeType = "audio/raw",
            pcmEncoding = 21 // C.ENCODING_PCM_24BIT
        )
        assertEquals(UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY, pcm24Support)
        assertTrue(UsbFormatSupport.isFormatSupported(true, true, "audio/raw", 21))

        val pcm16Support = UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 2)
        assertEquals(UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY, pcm16Support)

        val pcm32Support = UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 22)
        assertEquals(UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY, pcm32Support)

        val floatSupport = UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 4)
        assertEquals(UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY, floatSupport)

        // When USB exclusive mode is disabled, delegates to underlying sink (-1)
        val disabledSupport = UsbFormatSupport.getFormatSupport(false, true, "audio/flac", -1)
        assertEquals(-1, disabledSupport)
    }

    @Test
    fun configureRejectsEncodedFlacFormat() {
        var fallbackCalled = false
        var fallbackReason: String? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ -> FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount) },
            onFallbackTriggered = {
                fallbackCalled = true
                fallbackReason = it
            }
        )

        assertFailsWith<UsbConfigurationException> {
            sm.configure(
                formatRate = 44100,
                formatBitDepth = 16,
                formatChannels = 2,
                availableAltSettings = sampleAltSettings(),
                sampleMimeType = "audio/flac"
            )
        }

        assertTrue(fallbackCalled)
        assertEquals(UsbSinkState.FALLBACK, sm.state)
        assertTrue(fallbackReason?.contains("audio/flac") == true)
    }

    @Test
    fun fallbackWithFailingEngineBridge() {
        var fallbackCalled = false
        var fallbackReason: String? = null
        val failingBridge = FakeUsbAudioSinkBridge(96000, 3, 2).apply {
            // Simulate SET_CUR or claim failure during prepare
            isPrepared = false
        }
        val sm = UsbSinkStateMachine(
            bridgeFactory = { _, _, _ ->
                object : UsbAudioSinkBridge by failingBridge {
                    override fun prepare(): Boolean = false // Simulate prepare() failure
                }
            },
            onFallbackTriggered = {
                fallbackCalled = true
                fallbackReason = it
            }
        )

        assertFailsWith<UsbConfigurationException> {
            sm.configure(96000, 24, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        }

        assertTrue(fallbackCalled)
        assertEquals(UsbSinkState.FALLBACK, sm.state)
        assertTrue(fallbackReason?.contains("Failed to prepare") == true)
    }

    @Test
    fun testInt24FloatIdentityMathAndPacking() {
        val criticalInt24Values = intArrayOf(
            -8388608, // -2^23 (min)
            -8388607,
            -1000000,
            -65536,
            -256,
            -1,
            0,
            1,
            256,
            65536,
            1000000,
            8388606,
            8388607 // 2^23 - 1 (max)
        )

        // Math identity check: int24 -> float -> int24
        for (v in criticalInt24Values) {
            val fl = v / 8388608.0f
            val recovered = (fl.toDouble() * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
            assertEquals(v, recovered, "Failed exact recovery for $v")
        }

        // Stepped sweep across entire range (-8388608 to 8388607 with step 511)
        var sample = -8388608
        while (sample <= 8388607) {
            val fl = sample / 8388608.0f
            val recovered = (fl.toDouble() * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
            assertEquals(sample, recovered, "Failed exact recovery for stepped sample $sample")
            sample += 511
        }

        // Feed float samples through UsbSinkStateMachine configured for 24-bit 3-byte subslot
        var createdBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { createdBridge = it }
            }
        )

        // Filter altSettings to pick alt 3 (subslot 3, 24-bit packed)
        val alt3Only = sampleAltSettings().filter { it.subslotSize == 3 }
        sm.configure(
            formatRate = 96000,
            formatBitDepth = 24,
            formatChannels = 2,
            availableAltSettings = alt3Only,
            isFloat = true,
            sampleMimeType = "audio/raw"
        )
        sm.play()

        val bridge = createdBridge!!
        // Create 5 frames of stereo floats for: -8388608, -1, 0, 1, 8388607
        val testPairs = listOf(
            Pair(-8388608, -8388608),
            Pair(-1, -1),
            Pair(0, 0),
            Pair(1, 1),
            Pair(8388607, 8388607)
        )
        val floatBuf = ByteBuffer.allocate(testPairs.size * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for ((left, right) in testPairs) {
            floatBuf.putFloat(left / 8388608.0f)
            floatBuf.putFloat(right / 8388608.0f)
        }
        floatBuf.flip()

        val consumed = sm.handleBuffer(floatBuf, 0L)
        assertTrue(consumed)

        // Verify written bytes in bridge: 5 frames * 2 ch * 3 bytes = 30 bytes
        assertEquals(30, bridge.writtenBytes.size)
        val readBack = ByteBuffer.wrap(bridge.writtenBytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        for ((expectedLeft, expectedRight) in testPairs) {
            val b0 = readBack.get().toInt() and 0xFF
            val b1 = readBack.get().toInt() and 0xFF
            val b2 = readBack.get().toInt()
            val leftInt = (b0 or (b1 shl 8) or (b2 shl 16))

            val rb0 = readBack.get().toInt() and 0xFF
            val rb1 = readBack.get().toInt() and 0xFF
            val rb2 = readBack.get().toInt()
            val rightInt = (rb0 or (rb1 shl 8) or (rb2 shl 16))

            assertEquals(expectedLeft, leftInt, "Left channel mismatch for $expectedLeft")
            assertEquals(expectedRight, rightInt, "Right channel mismatch for $expectedRight")
        }
    }

    @Test
    fun testSessionReuseAcrossTracksViaReconfigure() {
        var bridgeCreationCount = 0
        var lastBridge: FakeUsbAudioSinkBridge? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                bridgeCreationCount++
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { lastBridge = it }
            }
        )

        // Track 1: 44.1 kHz, 16-bit
        val conf1 = sm.configure(
            formatRate = 44100,
            formatBitDepth = 16,
            formatChannels = 2,
            availableAltSettings = sampleAltSettings(),
            sampleMimeType = "audio/raw"
        )
        assertTrue(conf1)
        assertEquals(1, bridgeCreationCount)
        val bridge1 = lastBridge!!
        assertTrue(bridge1.isPrepared)
        sm.play()

        // Track 2: 96 kHz, 24-bit (format changes across track boundary)
        val conf2 = sm.configure(
            formatRate = 96000,
            formatBitDepth = 24,
            formatChannels = 2,
            availableAltSettings = sampleAltSettings(),
            sampleMimeType = "audio/raw"
        )
        assertTrue(conf2)
        // Session was reconfigured without dropping/recreating the bridge!
        assertEquals(1, bridgeCreationCount, "Bridge should have been reused across tracks via reconfigure()")
        assertEquals(bridge1, sm.activeBridge)
        assertEquals(96000, sm.currentRate)
        assertEquals(24, sm.currentBitDepth)
        assertEquals(sm.currentAltSetting, bridge1.reconfigureCalledWith?.first)
        assertEquals(96000, bridge1.reconfigureCalledWith?.second)
        assertFalse(bridge1.isStopped)
    }

    @Test
    fun testReconfigureFailureFallsBackToNewBridgeCreation() {
        var bridgeCreationCount = 0
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                bridgeCreationCount++
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).apply {
                    if (bridgeCreationCount == 1) {
                        reconfigureReturnSuccess = false // Fail reconfigure on track 1 bridge
                    }
                }
            }
        )

        // Track 1: 44.1 kHz, 16-bit
        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        assertEquals(1, bridgeCreationCount)

        // Track 2: 96 kHz, 24-bit -> reconfigure fails, so a new bridge is created cleanly
        val conf2 = sm.configure(96000, 24, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        assertTrue(conf2)
        assertEquals(2, bridgeCreationCount, "Should create new bridge when reconfigure fails")
        assertEquals(96000, sm.currentRate)
        assertEquals(24, sm.currentBitDepth)
    }

    @Test
    fun testPauseResumeCallsBridgeResume() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.play()

        // Feed enough audio to prime and start the bridge (20 ms at 44.1 kHz = 882 frames = 3528 bytes)
        val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm, 0L)
        assertTrue(bridgeRef.isRunning)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // Pause
        sm.pause()
        assertEquals(UsbSinkState.PAUSED, sm.state)
        assertFalse(bridgeRef.resumeCalled)

        // Resume via play()
        sm.play()
        assertTrue(bridgeRef.resumeCalled)
        assertEquals(UsbSinkState.PLAYING, sm.state)
    }

    @Test
    fun testSeekToZeroAnchorsBaseline() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.play()

        // Play 1 second (44100 frames)
        val pcm = ByteBuffer.allocate(44100 * 4).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm, 0L)
        assertEquals(44100L, bridgeRef.framesConsumed)
        assertEquals(1_000_000L, sm.getCurrentPositionUs(false))

        // Seek back to 0
        sm.flush()
        assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs(false))

        // Deliver first buffer after seek at position 0
        val pcmSeek = ByteBuffer.allocate(4410 * 4).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcmSeek, 0L)
        // Consumed 44100 + 4410 = 48510 frames. Delta from baseline (44100) is 4410 frames = 100,000 us
        val pos = sm.getCurrentPositionUs(false)
        assertEquals(100_000L, pos)
    }

    @Test
    fun testSoftwareVolumeScalingWhenHardwareVolumeUnsupported() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.hasHardwareVolume = false
        sm.setVolume(0.5f) // -6 dB scaling

        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putShort(10000.toShort()) // L
        pcm.putShort((-10000).toShort()) // R
        pcm.flip()

        sm.handleBuffer(pcm, 0L)
        val written = bridgeRef.writtenBytes
        assertEquals(4, written.size)
        val outBuf = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val outL = outBuf.short
        val outR = outBuf.short
        assertEquals(5000.toShort(), outL)
        assertEquals((-5000).toShort(), outR)
    }

    @Test
    fun testTrackTransitionPreservesPlaybackAcrossResetWithoutPlayCall() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.play()

        // Feed enough audio to prime and start the bridge
        val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm, 0L)
        assertTrue(bridgeRef.isRunning)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // ExoPlayer track transition sequence: pause -> flush -> reset(preservePlayRequested = true)
        sm.pause()
        assertEquals(UsbSinkState.PAUSED, sm.state)
        sm.flush(preservePlayRequested = false)
        sm.reset(preservePlayRequested = true)

        assertEquals(UsbSinkState.CONFIGURED, sm.state)
        assertTrue(sm.isActive)
        assertTrue(sm.isPlayRequested)
        bridgeRef.resumeCalled = false

        // Next track feeds buffers directly without ExoPlayer calling play()
        val pcmTrack2 = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val consumed = sm.handleBuffer(pcmTrack2, 0L)
        assertTrue(consumed)
        assertTrue(bridgeRef.resumeCalled)
        assertEquals(UsbSinkState.PLAYING, sm.state)
    }

    @Test
    fun testAutoStartNativeStreamerOnHandleBufferWhenPlayRequested() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        assertEquals(UsbSinkState.CONFIGURED, sm.state)
        assertFalse(bridgeRef.isRunning)

        // Player is already playing (e.g. play() called before buffer arrival)
        sm.play()
        assertTrue(sm.isPlayRequested)
        assertFalse(bridgeRef.isRunning) // Not yet primed

        // Buffer arrives
        val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val consumed = sm.handleBuffer(pcm, 0L)
        assertTrue(consumed)
        assertTrue(bridgeRef.isRunning) // Automatically started on priming
        assertEquals(UsbSinkState.PLAYING, sm.state)
    }

    @Test
    fun testResetKeepsConfiguredStateWhileReleaseSetsUnconfigured() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        assertEquals(UsbSinkState.CONFIGURED, sm.state)

        // reset() must preserve CONFIGURED state and not release bridge
        sm.reset(preservePlayRequested = false)
        assertEquals(UsbSinkState.CONFIGURED, sm.state)
        assertTrue(sm.isActive)
        assertFalse(bridgeRef.isStopped)

        // release() must set UNCONFIGURED and release bridge
        sm.release()
        assertEquals(UsbSinkState.UNCONFIGURED, sm.state)
        assertFalse(sm.isActive)
        assertTrue(bridgeRef.isStopped)
    }

    @Test
    fun testPauseFlushResetPlayTransition() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.play()

        val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm, 0L)
        assertTrue(bridgeRef.isRunning)
        assertFalse(bridgeRef.isPaused)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // Pause
        sm.pause()
        assertEquals(UsbSinkState.PAUSED, sm.state)
        assertTrue(bridgeRef.isPaused)

        // Flush & Reset
        sm.flush(preservePlayRequested = false)
        sm.reset(preservePlayRequested = false)
        assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs())

        // Play requested before next buffer
        sm.play()
        assertTrue(sm.isPlayRequested)
        assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs())

        // Buffer arrives
        val pcmNext = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val consumed = sm.handleBuffer(pcmNext, 0L)
        assertTrue(consumed)
        assertFalse(bridgeRef.isPaused)
        assertEquals(UsbSinkState.PLAYING, sm.state)
        assertTrue(sm.getCurrentPositionUs() >= 0L)
    }

    @Test
    fun testTrackSwitchWithPlayerIsPlayingWithoutPause() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.play()

        val pcm1 = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm1, 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // Player switches track while playing: reset with preservePlayRequested = true
        sm.reset(preservePlayRequested = true)
        assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs())
        assertTrue(sm.isPlayRequested)
        assertTrue(bridgeRef.isPaused)

        // Track 2 buffer arrives
        val pcm2 = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val consumed = sm.handleBuffer(pcm2, 0L)
        assertTrue(consumed)
        assertFalse(bridgeRef.isPaused)
        assertEquals(UsbSinkState.PLAYING, sm.state)
        assertTrue(sm.getCurrentPositionUs() >= 0L)
    }

    @Test
    fun testSameFormatConfigureAfterReset() {
        var createCount = 0
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                createCount++
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        assertEquals(1, createCount)
        sm.play()

        val pcm1 = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm1, 0L)

        // Track ends / seek: reset
        sm.reset(preservePlayRequested = true)
        assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs())

        // Next track has identical format: configure() gapless fast-path
        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        // Bridge must NOT be recreated
        assertEquals(1, createCount)
        assertFalse(bridgeRef.isPaused)

        val pcm2 = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val consumed = sm.handleBuffer(pcm2, 0L)
        assertTrue(consumed)
        assertEquals(UsbSinkState.PLAYING, sm.state)
        assertTrue(sm.getCurrentPositionUs() >= 0L)
    }

    @Test
    fun testSeekWhilePaused() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.play()

        val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm, 0L)

        // User pauses
        sm.pause()
        assertEquals(UsbSinkState.PAUSED, sm.state)
        assertTrue(bridgeRef.isPaused)

        // User seeks: flush while paused
        sm.flush(preservePlayRequested = false)
        assertFalse(sm.isPlayRequested)
        assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs())

        // Decoder feeds seek pre-roll buffer while player is paused
        val seekPreRoll = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val consumed = sm.handleBuffer(seekPreRoll, 0L)
        assertTrue(consumed)
        // Must stay unplayed while not play-requested and not start streaming audio out
        assertEquals(UsbSinkState.CONFIGURED, sm.state)
        assertTrue(bridgeRef.isPaused)

        // User unpauses
        sm.play()
        assertFalse(bridgeRef.isPaused)
        assertEquals(UsbSinkState.PLAYING, sm.state)
    }

    @Test
    fun testRapidPlayPauseToggles() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

        // Rapid toggles
        sm.play()
        sm.pause()
        sm.play()
        sm.pause()
        sm.play()

        assertTrue(sm.isPlayRequested)
        sm.handleBuffer(pcm, 0L)
        assertTrue(bridgeRef.isRunning)
        assertFalse(bridgeRef.isPaused)
        assertEquals(UsbSinkState.PLAYING, sm.state)
    }

    @Test
    fun testPositionBaselineUntilFirstBufferPostReset() {
        lateinit var bridgeRef: FakeUsbAudioSinkBridge
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridgeRef = it }
            }
        )

        sm.configure(44100, 16, 2, sampleAltSettings(), sampleMimeType = "audio/raw")
        sm.play()

        val pcm1 = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm1, 0L)
        assertTrue(sm.getCurrentPositionUs() >= 0L)

        sm.reset(preservePlayRequested = false)

        // Multiple calls post-reset must all return CURRENT_POSITION_NOT_SET
        for (i in 0 until 10) {
            assertEquals(UsbSinkStateMachine.CURRENT_POSITION_NOT_SET, sm.getCurrentPositionUs(), "Position attempt $i should be CURRENT_POSITION_NOT_SET post-reset")
        }

        val pcm2 = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        sm.handleBuffer(pcm2, 0L)

        // Once first buffer arrives, position becomes valid non-negative
        assertTrue(sm.getCurrentPositionUs() >= 0L)
    }

    @Test
    fun testPositionReportingAfterResetABComparison() {
        // A/B comparison test: CURRENT_POSITION_NOT_SET vs 0L
        // Simulates MediaCodecAudioRenderer position tracking across track transition / seek
        class SimulatedAudioRenderer(val sinkPositionSupplier: () -> Long) {
            var currentPositionUs: Long = 0L
            var allowPositionDiscontinuity: Boolean = false

            fun onPositionReset(positionUs: Long) {
                currentPositionUs = positionUs
                allowPositionDiscontinuity = true
            }

            fun updateCurrentPosition(): Long {
                val newPositionUs = sinkPositionSupplier()
                if (newPositionUs != UsbSinkStateMachine.CURRENT_POSITION_NOT_SET) {
                    currentPositionUs = if (allowPositionDiscontinuity) newPositionUs else maxOf(currentPositionUs, newPositionUs)
                    allowPositionDiscontinuity = false
                }
                return currentPositionUs
            }
        }

        // Scenario 1: Transition/seek to 30 seconds (30_000_000 us)
        // With CURRENT_POSITION_NOT_SET (Option A):
        var simulatedSinkPos = UsbSinkStateMachine.CURRENT_POSITION_NOT_SET
        val rendererA = SimulatedAudioRenderer { simulatedSinkPos }
        rendererA.onPositionReset(30_000_000L)

        // Poll position before first buffer arrives (e.g. during track transition / decoding)
        val posBeforeBufferA = rendererA.updateCurrentPosition()
        assertEquals(30_000_000L, posBeforeBufferA, "Clock should maintain anchor position while sink reports NOT_SET")
        assertTrue(rendererA.allowPositionDiscontinuity, "Discontinuity flag must remain true until first valid buffer")

        // First buffer arrives at 30s
        simulatedSinkPos = 30_000_000L
        val posAfterBufferA = rendererA.updateCurrentPosition()
        assertEquals(30_000_000L, posAfterBufferA)
        assertFalse(rendererA.allowPositionDiscontinuity)

        // Scenario 2: With 0L (Option B):
        var simulatedSinkPosB = 0L
        val rendererB = SimulatedAudioRenderer { simulatedSinkPosB }
        rendererB.onPositionReset(30_000_000L)

        // Poll position before first buffer arrives:
        val posBeforeBufferB = rendererB.updateCurrentPosition()
        // With 0L, position collapses to 0 and clears allowPositionDiscontinuity prematurely!
        assertEquals(0L, posBeforeBufferB, "0L prematurely crushes renderer position to 0")
        assertFalse(rendererB.allowPositionDiscontinuity, "0L prematurely clears allowPositionDiscontinuity")
    }
}
