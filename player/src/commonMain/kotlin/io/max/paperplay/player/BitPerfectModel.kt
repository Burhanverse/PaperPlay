package io.max.paperplay.player

/** User-facing bit-perfect output mode persisted across restarts. */
enum class BitPerfectMode {
    OFF,
    ON,
}

/** Sample representation along an audio output pipeline. */
enum class SampleEncoding {
    PCM_INT,
    PCM_FLOAT,
    DOP_24,
}

/** Underlying output transport verified by the platform audio backend. */
enum class TransportKind(val displayName: String) {
    NONE("Standard output"),
    WASAPI_EXCLUSIVE("WASAPI exclusive"),
    USB_UAC_DIRECT("USB Audio Direct (usbfs)"),
    ANDROID_BIT_PERFECT_MIXER("Android bit-perfect mixer"),
    ANDROID_MIXER("Android audio mixer"),
}

/** Feature flag gating cutover from legacy USB audio path to unified Bit-perfect path. */
const val USE_UNIFIED_BIT_PERFECT_DEFAULT = true

/** Reserved MIME type for future clean-room DoP (DSD over PCM) streams. */
const val MIME_AUDIO_DOP = "audio/x-paperplay-dop"

/** PCM/DoP stream format at a pipeline stage (source or verified output). */
data class PcmFormat(
    val sampleRateHz: Int,
    /** True source sample precision from track metadata (e.g. FLAC STREAMINFO) or container header. */
    val sourceBitDepth: Int,
    /** Precision active on this stage or wire (e.g. 16, 24, 32). */
    val wireBitDepth: Int,
    /** Container/subslot bit width on the wire (e.g. 16, 24, 32). */
    val containerBits: Int,
    val encoding: SampleEncoding,
    val channels: Int,
    /**
     * True when [encoding] is [SampleEncoding.PCM_FLOAT] carrying a `<= 24-bit` integer source
     * scaled by an exact power of two (`2^15` / `2^23`) with zero dither or gain.
     */
    val exactPowerOfTwoFloat: Boolean = false,
)

/** Verified transport telemetry attached to [OutputInfo]. */
data class TransportInfo(
    val kind: TransportKind = TransportKind.NONE,
    val verified: Boolean = false,
    val deviceName: String? = null,
    val deviceId: String? = null,
    val protocolVersion: String? = null,
    val confirmedRateHz: Int? = null,
    val confirmedBitDepth: Int? = null,
    val confirmedContainerBits: Int? = null,
    val confirmedAlt: Int? = null,
    val syncMode: String? = null,
    val hardwareVolume: String? = null,
    val underruns: Long = 0L,
    val errors: Long = 0L,
    val framesConsumed: Long = 0L,
    val unverifiedReason: String? = null,
) {
    val isExclusiveBitPerfectCapable: Boolean
        get() = when (kind) {
            TransportKind.WASAPI_EXCLUSIVE,
            TransportKind.USB_UAC_DIRECT,
            TransportKind.ANDROID_BIT_PERFECT_MIXER -> true
            TransportKind.NONE,
            TransportKind.ANDROID_MIXER -> false
        }
}

/** Real-time bit-perfect status driving both idle UI (Settings, USB DAC page) and player controls. */
sealed class BitPerfectStatus {
    data object Off : BitPerfectStatus()
    data class Standby(val deviceName: String? = null, val reason: String = "Waiting for playback") : BitPerfectStatus()
    data class PermissionRequired(val deviceName: String, val deviceId: String? = null) : BitPerfectStatus()
    data class Active(
        val transportLabel: String,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val deviceName: String? = null,
    ) : BitPerfectStatus()
    data class Bypassed(val reason: String) : BitPerfectStatus()
    data class Fallback(val reason: String) : BitPerfectStatus()
    data class Unsupported(val reason: String) : BitPerfectStatus()
}
