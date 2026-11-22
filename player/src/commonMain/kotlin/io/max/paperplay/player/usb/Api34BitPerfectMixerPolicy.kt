package io.max.paperplay.player.usb

/**
 * Pure Kotlin policy & 5-gate verifier for Android 14+ (API 34+) `MIXER_BEHAVIOR_BIT_PERFECT`.
 * Must remain Android-free in `commonMain` so JVM unit tests can exercise every gate independently.
 */
data class Api34MixerOption(
    val encoding: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val isBitPerfectBehavior: Boolean,
)

data class Api34MixerProbeInput(
    val devFlagEnabled: Boolean,
    val sdkInt: Int,
    val hasUsbAudioOutputDevice: Boolean,
    val supportedOptions: List<Api34MixerOption>,
    val requestedRateHz: Int,
    val requestedChannels: Int,
    val setPreferredSucceeded: Boolean,
    val readbackOption: Api34MixerOption?,
    val floatOutputDisabled: Boolean,
    val dspAndEffectsBypassed: Boolean,
    val audioTrackEncoding: Int,
    val audioTrackRateHz: Int,
    val audioTrackChannels: Int,
)

sealed interface Api34MixerVerification {
    data class Verified(
        val encoding: Int,
        val bitDepth: Int,
        val sampleRateHz: Int,
        val channels: Int,
    ) : Api34MixerVerification

    data class Rejected(
        val failedGate: Int,
        val reason: String,
    ) : Api34MixerVerification
}

object Api34BitPerfectMixerPolicy {
    /** Matches `android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED`. */
    const val ENCODING_PCM_24BIT_PACKED: Int = 21

    /** Matches `android.media.AudioFormat.ENCODING_PCM_32BIT`. */
    const val ENCODING_PCM_32BIT: Int = 22

    /** Matches `android.media.AudioFormat.ENCODING_PCM_FLOAT`. */
    const val ENCODING_PCM_FLOAT: Int = 4

    /** Matches `android.media.AudioFormat.ENCODING_PCM_16BIT`. */
    const val ENCODING_PCM_16BIT: Int = 2

    fun isSupportedIntEncoding(encoding: Int): Boolean =
        encoding == ENCODING_PCM_24BIT_PACKED || encoding == ENCODING_PCM_32BIT

    fun bitDepthForEncoding(encoding: Int): Int? = when (encoding) {
        ENCODING_PCM_24BIT_PACKED -> 24
        ENCODING_PCM_32BIT -> 32
        else -> null
    }

    /** Picks an integer 24/32-bit `MIXER_BEHAVIOR_BIT_PERFECT` entry matching rate and channels. */
    fun selectBestOption(
        supportedOptions: List<Api34MixerOption>,
        requestedRateHz: Int,
        requestedChannels: Int,
    ): Api34MixerOption? {
        if (requestedRateHz <= 0 || requestedChannels <= 0) return null
        val matching = supportedOptions.filter { opt ->
            opt.isBitPerfectBehavior &&
                isSupportedIntEncoding(opt.encoding) &&
                opt.sampleRateHz == requestedRateHz &&
                opt.channels == requestedChannels
        }
        return matching.firstOrNull { it.encoding == ENCODING_PCM_32BIT }
            ?: matching.firstOrNull { it.encoding == ENCODING_PCM_24BIT_PACKED }
    }

    /** Fail-closed 5-gate verifier required before reporting `ANDROID_BIT_PERFECT_MIXER` verified. */
    fun verifyAllFiveGates(input: Api34MixerProbeInput): Api34MixerVerification {
        // Gate 1: Developer opt-in, Android 14+ (API 34+), and active USB audio output device
        if (!input.devFlagEnabled) {
            return Api34MixerVerification.Rejected(1, "Dev flag dev_api34_bitperfect_mixer is off")
        }
        if (input.sdkInt < 34) {
            return Api34MixerVerification.Rejected(1, "Requires Android 14+ (API 34+), found API ${input.sdkInt}")
        }
        if (!input.hasUsbAudioOutputDevice) {
            return Api34MixerVerification.Rejected(1, "No USB audio output device active")
        }

        // Gate 2: Supported MIXER_BEHAVIOR_BIT_PERFECT with 24-bit packed or 32-bit integer PCM
        val selected = selectBestOption(
            supportedOptions = input.supportedOptions,
            requestedRateHz = input.requestedRateHz,
            requestedChannels = input.requestedChannels,
        ) ?: return Api34MixerVerification.Rejected(
            2,
            "No integer 24/32-bit MIXER_BEHAVIOR_BIT_PERFECT config for ${input.requestedRateHz} Hz / ${input.requestedChannels} ch",
        )

        // Gate 3: setPreferredMixerAttributes succeeded AND getPreferredMixerAttributes readback matches
        if (!input.setPreferredSucceeded) {
            return Api34MixerVerification.Rejected(3, "setPreferredMixerAttributes returned false")
        }
        val readback = input.readbackOption
            ?: return Api34MixerVerification.Rejected(3, "getPreferredMixerAttributes readback was null")
        if (!readback.isBitPerfectBehavior ||
            !isSupportedIntEncoding(readback.encoding) ||
            readback.sampleRateHz != input.requestedRateHz ||
            readback.channels != input.requestedChannels
        ) {
            return Api34MixerVerification.Rejected(
                3,
                "Preferred mixer readback mismatch: enc=${readback.encoding}, rate=${readback.sampleRateHz}, ch=${readback.channels}, bp=${readback.isBitPerfectBehavior}",
            )
        }

        // Gate 4: Float output disabled AND DSP/effects bypassed
        if (!input.floatOutputDisabled) {
            return Api34MixerVerification.Rejected(4, "Float output is enabled; API 34 mixer requires integer output")
        }
        if (!input.dspAndEffectsBypassed) {
            return Api34MixerVerification.Rejected(4, "DSP or audio effects active")
        }

        // Gate 5: Actual AudioTrack format verified at exact integer encoding, rate, and channels
        val bitDepth = bitDepthForEncoding(input.audioTrackEncoding)
        if (bitDepth == null ||
            input.audioTrackEncoding != readback.encoding ||
            input.audioTrackRateHz != input.requestedRateHz ||
            input.audioTrackChannels != input.requestedChannels
        ) {
            return Api34MixerVerification.Rejected(
                5,
                "AudioTrack format mismatch: enc=${input.audioTrackEncoding}, rate=${input.audioTrackRateHz}, ch=${input.audioTrackChannels} (expected enc=${readback.encoding}/${selected.encoding})",
            )
        }

        return Api34MixerVerification.Verified(
            encoding = input.audioTrackEncoding,
            bitDepth = bitDepth,
            sampleRateHz = input.audioTrackRateHz,
            channels = input.audioTrackChannels,
        )
    }
}
