package io.max.paperplay.player.usb

/** USB Audio Class standard versions. */
enum class UacVersion {
    UAC_1_0,
    UAC_2_0,
    UAC_3_0_UNSUPPORTED,
    UNKNOWN,
}

enum class UsbSpeed {
    LOW,
    FULL,
    HIGH,
    SUPER,
    UNKNOWN,
}

enum class EndpointSyncType {
    NONE,
    ASYNCHRONOUS,
    ADAPTIVE,
    SYNCHRONOUS;

    companion object {
        fun fromAttributes(bmAttributes: Int): EndpointSyncType =
            when ((bmAttributes shr 2) and 0x03) {
                1 -> ASYNCHRONOUS
                2 -> ADAPTIVE
                3 -> SYNCHRONOUS
                else -> NONE
            }
    }
}

enum class EndpointUsageType {
    DATA,
    FEEDBACK,
    IMPLICIT_FEEDBACK,
    UNKNOWN;

    companion object {
        fun fromAttributes(bmAttributes: Int): EndpointUsageType =
            when ((bmAttributes shr 4) and 0x03) {
                0 -> DATA
                1 -> FEEDBACK
                2 -> IMPLICIT_FEEDBACK
                else -> UNKNOWN
            }
    }
}

enum class AudioFormatTag(val value: Int) {
    PCM(0x0001),
    PCM8(0x0002),
    IEEE_FLOAT(0x0003),
    ALAW(0x0004),
    MULAW(0x0005),
    RAW_DATA(0x8000), // DSD / raw bitstream marker
    OTHER(0xFFFF);

    companion object {
        fun fromTag(tag: Int): AudioFormatTag =
            entries.firstOrNull { it.value == tag } ?: OTHER
    }
}

data class UsbEndpointDescriptor(
    val address: Int,
    val isOut: Boolean,
    val syncType: EndpointSyncType,
    val usageType: EndpointUsageType,
    val maxPacketSize: Int,
    val interval: Int,
    val refresh: Int = 0,
    val synchAddress: Int = 0,
    val csBmAttributes: Int = 0x01,
) {
    /**
     * UAC 1.0 Class-Specific AS Isochronous Audio Data Endpoint Descriptor `bmAttributes` bit 0:
     * Sampling Frequency control (`SAMPLING_FREQ_CONTROL` = 0x01) is supported.
     */
    val supportsSampleRateControl: Boolean get() = (csBmAttributes and 0x01) != 0
}

data class UsbClockSource(
    val id: Int,
    val clockType: Int,
    val isProgrammable: Boolean,
    val supportsFrequencySet: Boolean,
    val supportsFrequencyGet: Boolean,
    val supportsClockValid: Boolean,
    val assocTerminal: Int,
)

data class UsbClockSelector(
    val id: Int,
    val inputClockIds: List<Int>,
)

data class UsbClockMultiplier(
    val id: Int,
    val sourceClockId: Int,
)

data class UsbInputTerminal(
    val id: Int,
    val terminalType: Int,
    val assocTerminal: Int,
    val clockSourceId: Int, // UAC2
    val channelCount: Int,
    val channelConfig: Long,
) {
    val isUsbStreaming: Boolean get() = terminalType == 0x0101
}

data class UsbOutputTerminal(
    val id: Int,
    val terminalType: Int,
    val assocTerminal: Int,
    val sourceId: Int,
    val clockSourceId: Int, // UAC2
) {
    val isPhysicalOutput: Boolean get() = terminalType != 0x0101
}

data class UsbFeatureUnit(
    val id: Int,
    val sourceId: Int,
    val controlSize: Int,
    val bmaControls: List<Long>,
    val hasMasterMute: Boolean,
    val hasMasterVolume: Boolean,
    val hasChannelVolume: Boolean,
    val hasChannelMute: Boolean = false,
)

data class UsbSelectorUnit(
    val id: Int,
    val sourceIds: List<Int>,
)

data class UsbMixerUnit(
    val id: Int,
    val sourceIds: List<Int>,
)

data class UsbAudioAltSetting(
    val interfaceNumber: Int,
    val altSetting: Int,
    val terminalLink: Int,
    val formatTag: AudioFormatTag,
    val bmFormats: Long, // UAC2 bitmap (bit 0 PCM, bit 31 RAW_DATA)
    val channelCount: Int,
    val subslotSize: Int, // Bytes per sample: 1, 2, 3, or 4
    val bitResolution: Int, // Bits of precision: 16, 24, 32
    val sampleRates: List<Int>, // Discrete rates (UAC1 or queried)
    val continuousRateRange: Pair<Int, Int>? = null, // UAC1 continuous (min, max)
    val dataEndpoint: UsbEndpointDescriptor?,
    val feedbackEndpoint: UsbEndpointDescriptor? = null,
    val isDsdRawCapable: Boolean,
    val isDsdDoPCapable: Boolean,
) {
    val isOut: Boolean get() = dataEndpoint?.isOut == true

    /**
     * Computes the native `ep_attributes` byte passed to Rust `program_sample_rate` / `UsbSessionCore`:
     * - Bits 0..6: Class-specific AS endpoint `bmAttributes` (bit 0 = `UAC_EP_CS_ATTR_SAMPLE_RATE`).
     * - Bit 7 (`0x80`): set when this alternate setting advertises a single discrete rate equal to [targetRate]
     *   (allowing safe continuation if `SET_CUR` stalls on a fixed-rate alt setting).
     */
    fun computeNativeEpAttributes(targetRate: Int): Int {
        val rawAttr = (dataEndpoint?.csBmAttributes ?: 0x01) and 0x7F
        val isSingleDiscrete = continuousRateRange == null && sampleRates.size == 1 && sampleRates[0] == targetRate
        return if (isSingleDiscrete) (rawAttr or 0x80) else rawAttr
    }
}

data class UsbAudioDeviceDescription(
    val uacVersion: UacVersion,
    val isSupported: Boolean,
    val unsupportedReason: String? = null,
    val acInterfaceNumber: Int = 0,
    val clockSources: List<UsbClockSource> = emptyList(),
    val clockSelectors: List<UsbClockSelector> = emptyList(),
    val clockMultipliers: List<UsbClockMultiplier> = emptyList(),
    val inputTerminals: List<UsbInputTerminal> = emptyList(),
    val outputTerminals: List<UsbOutputTerminal> = emptyList(),
    val featureUnits: List<UsbFeatureUnit> = emptyList(),
    val selectorUnits: List<UsbSelectorUnit> = emptyList(),
    val mixerUnits: List<UsbMixerUnit> = emptyList(),
    val altSettings: List<UsbAudioAltSetting> = emptyList(),
    val hasHardwareVolume: Boolean = false,
    val rawDsdCapable: Boolean = false,
    val doPCapable: Boolean = false,
) {
    val playbackAltSettings: List<UsbAudioAltSetting> get() = altSettings.filter { it.isOut }
    val captureAltSettings: List<UsbAudioAltSetting> get() = altSettings.filter { !it.isOut }
}

data class UsbStreamStats(
    val framesConsumed: Long,
    val underruns: Long,
    val urbErrors: Long,
    val ringFillBytes: Long,
    val ringCapacity: Long,
    val isRunning: Boolean,
    val sampleRate: Int,
    val isPaused: Boolean = false,
    val isFlushing: Boolean = false,
)

data class UsbExclusiveStatus(
    val enabled: Boolean = true,
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val vidPid: String? = null,
    val uacVersion: String? = null,
    val activeRate: Int? = null,
    val activeBitDepth: Int? = null,
    val activeChannels: Int? = null,
    val endpointMode: String? = null,
    val isHighSpeed: Boolean = false,
    val hardwareVolume: String? = null,
    val underruns: Long = 0L,
    val urbErrors: Long = 0L,
    val framesConsumed: Long = 0L,
    val isBitPerfect: Boolean = false,
    val statusReason: String = "No USB DAC connected"
)

/**
 * Format support evaluator for USB exclusive audio mode.
 * Evaluates whether formats are supported directly or need decoder synthesis.
 * Encoded formats (audio/flac, audio/mp4a-latm, etc.) MUST report UNSUPPORTED
 * so MediaCodecAudioRenderer decodes them to raw PCM before handing them to the sink.
 */
object UsbFormatSupport {
    const val SINK_FORMAT_UNSUPPORTED = 0
    const val SINK_FORMAT_SUPPORTED_WITH_TRANSCODING = 1
    const val SINK_FORMAT_SUPPORTED_DIRECTLY = 2

    /**
     * Evaluates format support for USB exclusive mode using static capabilities only
     * (never transient sink state or connection state).
     * Returns:
     * - SINK_FORMAT_SUPPORTED_DIRECTLY (2) for supported raw PCM formats (16/24/32-bit PCM & float, any positive rate).
     * - SINK_FORMAT_UNSUPPORTED (0) for all encoded formats (e.g. audio/flac) or unsupported PCM.
     * - -1 when USB exclusive mode is disabled (delegate 1:1 to underlying AudioSink).
     */
    fun getFormatSupport(
        isUsbExclusiveMode: Boolean,
        @Suppress("UNUSED_PARAMETER") isUsbConnected: Boolean = true,
        sampleMimeType: String?,
        pcmEncoding: Int,
        channelCount: Int = 2,
        sampleRate: Int = 44100
    ): Int {
        if (!isUsbExclusiveMode) {
            return -1 // Feature off: delegate 1:1 to normal sink
        }
        if (sampleMimeType != null && sampleMimeType != "audio/raw") {
            // Never allow codec bypass / direct passthrough for encoded formats in USB mode!
            // MediaCodecAudioRenderer must decode encoded streams (FLAC, AAC, Vorbis, etc.) to PCM.
            return SINK_FORMAT_UNSUPPORTED
        }
        if (channelCount > 2 || channelCount == 0 || channelCount < -1) {
            return SINK_FORMAT_UNSUPPORTED
        }
        // Accept any positive sample rate (or unspecified -1); if the connected DAC does not
        // support a given rate, configure() falls back cleanly to the normal sink.
        if (sampleRate != -1 && sampleRate <= 0) {
            return SINK_FORMAT_UNSUPPORTED
        }
        return when (pcmEncoding) {
            2 /* C.ENCODING_PCM_16BIT */,
            21 /* C.ENCODING_PCM_24BIT */,
            22 /* C.ENCODING_PCM_32BIT */,
            4 /* C.ENCODING_PCM_FLOAT */,
            -1 /* Format.NO_VALUE */,
            0 /* C.ENCODING_INVALID / unspecified */ -> SINK_FORMAT_SUPPORTED_DIRECTLY
            else -> SINK_FORMAT_UNSUPPORTED
        }
    }

    fun isFormatSupported(
        isUsbExclusiveMode: Boolean,
        isUsbConnected: Boolean,
        sampleMimeType: String?,
        pcmEncoding: Int,
        channelCount: Int = 2,
        sampleRate: Int = 44100
    ): Boolean {
        val support = getFormatSupport(
            isUsbExclusiveMode = isUsbExclusiveMode,
            isUsbConnected = isUsbConnected,
            sampleMimeType = sampleMimeType,
            pcmEncoding = pcmEncoding,
            channelCount = channelCount,
            sampleRate = sampleRate
        )
        return support == SINK_FORMAT_SUPPORTED_DIRECTLY
    }
}

