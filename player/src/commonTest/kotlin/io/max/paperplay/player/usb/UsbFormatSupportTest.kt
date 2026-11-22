package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbFormatSupportTest {

    private val testEncodings = listOf(
        2,  // C.ENCODING_PCM_16BIT
        21, // C.ENCODING_PCM_24BIT
        22, // C.ENCODING_PCM_32BIT
        4,  // C.ENCODING_PCM_FLOAT
        -1, // Format.NO_VALUE
        0   // C.ENCODING_INVALID
    )

    private val testSampleRates = listOf(
        4000, 8000, 11025, 16000, 22050, 32000, 44100, 48000,
        88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000, -1
    )

    private val testChannels = listOf(1, 2)

    @Test
    fun testMatrixAllEncodingsRatesChannelsSupportedInUsbMode() {
        // In USB exclusive mode with connected DAC, every standard PCM encoding x rate (8k..384k) x channel (1, 2)
        // MUST return SINK_FORMAT_SUPPORTED_DIRECTLY (2) and isFormatSupported == true.
        for (enc in testEncodings) {
            for (rate in testSampleRates) {
                for (ch in testChannels) {
                    val supportRawMime = UsbFormatSupport.getFormatSupport(
                        isUsbExclusiveMode = true,
                        isUsbConnected = true,
                        sampleMimeType = "audio/raw",
                        pcmEncoding = enc,
                        channelCount = ch,
                        sampleRate = rate
                    )
                    assertEquals(
                        UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY,
                        supportRawMime,
                        "Failed for enc=$enc rate=$rate ch=$ch with audio/raw"
                    )
                    assertTrue(
                        UsbFormatSupport.isFormatSupported(
                            isUsbExclusiveMode = true,
                            isUsbConnected = true,
                            sampleMimeType = "audio/raw",
                            pcmEncoding = enc,
                            channelCount = ch,
                            sampleRate = rate
                        )
                    )

                    // Also verify with null sampleMimeType (probe format before decode)
                    val supportNullMime = UsbFormatSupport.getFormatSupport(
                        isUsbExclusiveMode = true,
                        isUsbConnected = true,
                        sampleMimeType = null,
                        pcmEncoding = enc,
                        channelCount = ch,
                        sampleRate = rate
                    )
                    assertEquals(
                        UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY,
                        supportNullMime,
                        "Failed for enc=$enc rate=$rate ch=$ch with null mime"
                    )
                }
            }
        }
    }

    @Test
    fun testDelegateModeReturnsMinusOneAndStaticSupportIgnoresTransientConnection() {
        // When USB exclusive mode is disabled, format evaluator returns -1 so sink delegates 1:1.
        // When USB exclusive mode is enabled, format support depends ONLY on static capabilities,
        // never on transient connection or sink state.
        for (enc in testEncodings) {
            for (rate in listOf(44100, 48000, 96000, 768000)) {
                for (ch in testChannels) {
                    val exclFalse = UsbFormatSupport.getFormatSupport(
                        isUsbExclusiveMode = false,
                        isUsbConnected = true,
                        sampleMimeType = "audio/raw",
                        pcmEncoding = enc,
                        channelCount = ch,
                        sampleRate = rate
                    )
                    assertEquals(-1, exclFalse, "Should delegate (-1) when exclusive mode is false")

                    val connFalse = UsbFormatSupport.getFormatSupport(
                        isUsbExclusiveMode = true,
                        isUsbConnected = false,
                        sampleMimeType = "audio/raw",
                        pcmEncoding = enc,
                        channelCount = ch,
                        sampleRate = rate
                    )
                    assertEquals(
                        UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY,
                        connFalse,
                        "Static format support must not depend on transient DAC connection state"
                    )
                }
            }
        }
    }

    @Test
    fun testEncodedFormatsUnsupportedInUsbMode() {
        // Encoded formats (FLAC, AAC, MP3, Vorbis, Opus) MUST report SINK_FORMAT_UNSUPPORTED (0)
        // so MediaCodecAudioRenderer instantiates a software/hardware decoder instead of passthrough.
        val encodedMimes = listOf(
            "audio/flac",
            "audio/mp4a-latm",
            "audio/mpeg",
            "audio/ogg",
            "audio/webm",
            "audio/x-wav"
        )
        for (mime in encodedMimes) {
            val support = UsbFormatSupport.getFormatSupport(
                isUsbExclusiveMode = true,
                isUsbConnected = true,
                sampleMimeType = mime,
                pcmEncoding = -1
            )
            assertEquals(
                UsbFormatSupport.SINK_FORMAT_UNSUPPORTED,
                support,
                "Encoded format $mime must be unsupported directly"
            )
            assertFalse(
                UsbFormatSupport.isFormatSupported(
                    isUsbExclusiveMode = true,
                    isUsbConnected = true,
                    sampleMimeType = mime,
                    pcmEncoding = -1
                )
            )
        }
    }

    @Test
    fun testMultiChannelAndInvalidRatesUnsupported() {
        // 5.1 surround / 7.1 audio is not supported on stereo USB DAC endpoints
        val surroundSupport = UsbFormatSupport.getFormatSupport(
            isUsbExclusiveMode = true,
            isUsbConnected = true,
            sampleMimeType = "audio/raw",
            pcmEncoding = 2,
            channelCount = 6,
            sampleRate = 48000
        )
        assertEquals(UsbFormatSupport.SINK_FORMAT_UNSUPPORTED, surroundSupport)

        // Any positive rate (including 4 kHz, 705.6 kHz, 768 kHz) is accepted statically
        assertEquals(
            UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY,
            UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 2, 2, 4000)
        )
        assertEquals(
            UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY,
            UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 22, 2, 768000)
        )

        // Zero or invalid negative rates (< -1) are unsupported
        val zeroRate = UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 2, 2, 0)
        assertEquals(UsbFormatSupport.SINK_FORMAT_UNSUPPORTED, zeroRate)

        val negativeRate = UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 2, 2, -44100)
        assertEquals(UsbFormatSupport.SINK_FORMAT_UNSUPPORTED, negativeRate)
    }

    @Test
    fun testFormatSupportDuringAndAfterResetAndFlush() {
        val altSettings = listOf(
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
                continuousRateRange = null,
                dataEndpoint = UsbEndpointDescriptor(1, true, EndpointSyncType.ADAPTIVE, EndpointUsageType.DATA, 192, 1, 0, 0),
                feedbackEndpoint = null,
                isDsdRawCapable = false,
                isDsdDoPCapable = false
            )
        )

        var lastRate = 0
        val fakeBridge = FakeUsbAudioSinkBridge(44100, 2, 2)
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                lastRate = rate
                fakeBridge
            }
        )

        sm.configure(44100, 16, 2, altSettings, isFloat = false)
        sm.play()

        // 1. During flush
        sm.flush()
        assertTrue(
            UsbFormatSupport.isFormatSupported(true, true, "audio/raw", 2),
            "Format support must remain true during flush"
        )
        assertFalse(
            UsbFormatSupport.isFormatSupported(true, true, "audio/flac", 21),
            "FLAC must remain unsupported during flush"
        )

        // 2. After reset
        sm.reset()
        assertTrue(
            UsbFormatSupport.isFormatSupported(true, true, "audio/raw", 21),
            "Format support must remain true after reset"
        )
        assertEquals(
            UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY,
            UsbFormatSupport.getFormatSupport(true, true, "audio/raw", 21, 2, 48000)
        )
    }

    @Test
    fun testTrackSelectionRegressionAcross10NextPresses() {
        // Regression test simulating 10 consecutive track switches with real renderer capabilities logic.
        // Verifies that:
        // 1. Encoded container (FLAC) returns UNSUPPORTED -> decoder is assigned.
        // 2. Decoded PCM format returns SUPPORTED_DIRECTLY (2) -> track is supported and selected.
        // 3. DAC lacking a sample rate (e.g. 88.2 kHz) reports supported during track selection,
        //    and gracefully delegates in configure() without deselecting the track.
        data class TestTrack(
            val id: Int,
            val mime: String,
            val rate: Int,
            val enc: Int,
            val channels: Int
        )

        val tracks = listOf(
            TestTrack(1, "audio/flac", 44100, 2, 2),
            TestTrack(2, "audio/flac", 48000, 21, 2),
            TestTrack(3, "audio/flac", 96000, 21, 2),
            TestTrack(4, "audio/flac", 88200, 2, 2),   // DAC lacks 88.2 kHz
            TestTrack(5, "audio/flac", 192000, 22, 2),  // DAC lacks 192 kHz
            TestTrack(6, "audio/raw", 44100, 2, 2),    // 16-bit stereo WAV
            TestTrack(7, "audio/raw", 48000, 22, 2),   // 32-bit WAV
            TestTrack(8, "audio/raw", 96000, 21, 2),   // 24-bit WAV
            TestTrack(9, "audio/raw", 44100, 4, 2),    // Float WAV
            TestTrack(10, "audio/flac", 48000, 2, 2)
        )

        val dacAltSettings = listOf(
            UsbAudioAltSetting(
                interfaceNumber = 1,
                altSetting = 1,
                terminalLink = 1,
                formatTag = AudioFormatTag.PCM,
                bmFormats = 1L,
                channelCount = 2,
                subslotSize = 2,
                bitResolution = 16,
                sampleRates = listOf(44100, 48000, 96000), // Note: no 88.2k or 192k
                continuousRateRange = null,
                dataEndpoint = UsbEndpointDescriptor(1, true, EndpointSyncType.ADAPTIVE, EndpointUsageType.DATA, 192, 1, 0, 0),
                feedbackEndpoint = null,
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
                subslotSize = 4,
                bitResolution = 24,
                sampleRates = listOf(44100, 48000, 96000),
                continuousRateRange = null,
                dataEndpoint = UsbEndpointDescriptor(1, true, EndpointSyncType.ADAPTIVE, EndpointUsageType.DATA, 384, 1, 0, 0),
                feedbackEndpoint = null,
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
                subslotSize = 4,
                bitResolution = 32,
                sampleRates = listOf(44100, 48000, 96000),
                continuousRateRange = null,
                dataEndpoint = UsbEndpointDescriptor(1, true, EndpointSyncType.ADAPTIVE, EndpointUsageType.DATA, 384, 1, 0, 0),
                feedbackEndpoint = null,
                isDsdRawCapable = false,
                isDsdDoPCapable = false
            )
        )

        var fallbackCount = 0
        var fallbackReason: String? = null
        val fakeBridge = FakeUsbAudioSinkBridge(44100, 2, 2)
        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ -> fakeBridge },
            onFallbackTriggered = { reason ->
                fallbackCount++
                fallbackReason = reason
            }
        )

        for (track in tracks) {
            // Step A: Track Selection Probe
            if (track.mime != "audio/raw") {
                // Container format probe: MUST return UNSUPPORTED so MediaCodec decodes
                val containerSupport = UsbFormatSupport.getFormatSupport(
                    isUsbExclusiveMode = true,
                    isUsbConnected = true,
                    sampleMimeType = track.mime,
                    pcmEncoding = track.enc,
                    channelCount = track.channels,
                    sampleRate = track.rate
                )
                assertEquals(
                    UsbFormatSupport.SINK_FORMAT_UNSUPPORTED,
                    containerSupport,
                    "Track ${track.id}: container ${track.mime} must require decoder synthesis"
                )
            }

            // Step B: MediaCodec probe format for decoded PCM output
            // MediaCodec probes the AudioSink with decoded raw PCM (audio/raw, rate, channels, enc)
            val pcmSupport = UsbFormatSupport.getFormatSupport(
                isUsbExclusiveMode = true,
                isUsbConnected = true,
                sampleMimeType = "audio/raw",
                pcmEncoding = track.enc,
                channelCount = track.channels,
                sampleRate = track.rate
            )
            assertEquals(
                UsbFormatSupport.SINK_FORMAT_SUPPORTED_DIRECTLY,
                pcmSupport,
                "Track ${track.id}: decoded PCM MUST report SINK_FORMAT_SUPPORTED_DIRECTLY (2) so track is selected"
            )

            // Step C: Sink Configure
            val bitDepth = when (track.enc) {
                2 -> 16
                21 -> 24
                22 -> 32
                4 -> 32
                else -> 16
            }
            val configured = try {
                sm.configure(
                    formatRate = track.rate,
                    formatBitDepth = bitDepth,
                    formatChannels = track.channels,
                    availableAltSettings = dacAltSettings,
                    isFloat = track.enc == 4
                )
            } catch (e: UsbConfigurationException) {
                false
            }

            if (track.rate in listOf(44100, 48000, 96000)) {
                assertTrue(configured, "Track ${track.id}: supported rate ${track.rate} Hz must configure successfully")
                assertTrue(sm.isActive, "Track ${track.id}: stateMachine must be active")
            } else {
                // For unsupported rates (88.2 kHz, 192 kHz), configure returns false and triggers fallback
                assertFalse(configured, "Track ${track.id}: rate ${track.rate} Hz unsupported by DAC must return false")
                assertTrue(fallbackReason?.contains("${track.rate} Hz") == true, "Fallback reason must mention rate")
            }

            // Step D: Track transition reset
            sm.flush()
            sm.reset()
        }

        assertEquals(2, fallbackCount, "Tracks 4 and 5 (88.2k and 192k) must fall back gracefully")
    }
}
