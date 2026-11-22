package io.max.paperplay.player

import kotlin.math.round

/** Result of [BitPerfectEvaluator.evaluate]. */
data class BitPerfectVerdict(
    val bitPerfect: Boolean,
    val note: String,
)

/**
 * Single pure bit-perfect policy evaluator shared by desktop (mpv) and Android.
 *
 * Rules:
 * 1. Transport must be verified ([TransportKind.WASAPI_EXCLUSIVE], [TransportKind.USB_UAC_DIRECT],
 *    or [TransportKind.ANDROID_BIT_PERFECT_MIXER]).
 * 2. Zero active DSP/effects/resampling/speed/software-volume changes.
 * 3. Sample rate and channel count must match between source and verified output.
 * 4. Format identity or exact lossless container widening:
 *    - `PCM_INT -> PCM_INT` with `output.wireBitDepth >= source.sourceBitDepth` and `containerBits >= sourceBitDepth` (zero-padded, no dither).
 *    - `PCM_FLOAT` carrying `<= 24-bit` integer source (`exactPowerOfTwoFloat == true`) back to `PCM_INT` >= `sourceBitDepth`.
 *    - `DOP_24 -> DOP_24` in 24/32-bit integer container at identical rate/channels.
 *    - NEVER bit-perfect for `> 24-bit` integer via float, truncation, rate/channel mismatch, non-power-of-two scaling, or dither.
 */
object BitPerfectEvaluator {
    private const val SCALE_I24 = 8_388_608.0f // 2^23 (exact in float32)
    private const val MIN_I24 = -8_388_608
    private const val MAX_I24 = 8_388_607

    /** Converts a signed 24-bit integer sample to float32 using exact power-of-two scaling (`2^23`). */
    fun int24ToFloat32Pow2(sample: Int): Float = sample.coerceIn(MIN_I24, MAX_I24).toFloat() / SCALE_I24

    /** Converts float32 back to signed 24-bit integer using exact power-of-two scaling (`2^23`). */
    fun float32ToInt24Pow2(sample: Float): Int = round(sample * SCALE_I24).toInt().coerceIn(MIN_I24, MAX_I24)

    fun evaluate(
        transport: TransportInfo,
        source: PcmFormat?,
        output: PcmFormat?,
        effects: List<String>,
        requestedOn: Boolean = true,
    ): BitPerfectVerdict {
        val speedEffect = effects.firstOrNull { it.startsWith("Speed ", ignoreCase = true) }
        if (speedEffect != null) {
            val speedText = speedEffect.substringAfter(' ').ifBlank { speedEffect }
            return BitPerfectVerdict(bitPerfect = false, note = "Not bit-perfect: speed $speedText")
        }
        if (effects.isNotEmpty()) {
            return BitPerfectVerdict(
                bitPerfect = false,
                note = "Not bit-perfect: ${effects.joinToString(", ")}",
            )
        }
        if (!requestedOn || !transport.verified || !transport.isExclusiveBitPerfectCapable) {
            val reason = transport.unverifiedReason
                ?: when (transport.kind) {
                    TransportKind.ANDROID_MIXER -> "Android audio mixer is not bit-perfect; connect a USB DAC with Bit-perfect enabled."
                    TransportKind.NONE -> "Turn on Bit-perfect for untouched exclusive output."
                    else -> "Exclusive transport could not be verified."
                }
            return BitPerfectVerdict(bitPerfect = false, note = reason)
        }
        if (source == null || output == null) {
            return BitPerfectVerdict(bitPerfect = false, note = "Waiting for decoded and output formats.")
        }
        if (source.sampleRateHz <= 0 || output.sampleRateHz <= 0 || source.sampleRateHz != output.sampleRateHz) {
            return BitPerfectVerdict(
                bitPerfect = false,
                note = "Not bit-perfect: resampled (${source.sampleRateHz} Hz -> ${output.sampleRateHz} Hz).",
            )
        }
        if (source.channels <= 0 || source.channels != output.channels) {
            return BitPerfectVerdict(
                bitPerfect = false,
                note = "Not bit-perfect: channel count changed (${source.channels} -> ${output.channels}).",
            )
        }

        val formatOk = isLosslessFormatPreserved(source, output)
        if (!formatOk) {
            return BitPerfectVerdict(
                bitPerfect = false,
                note = formatMismatchReason(source, output),
            )
        }

        val successNote = when (transport.kind) {
            TransportKind.WASAPI_EXCLUSIVE -> "Exclusive WASAPI output: samples reach the sound device unchanged."
            TransportKind.USB_UAC_DIRECT -> "Bit-perfect USB exclusive output: mixer, DSP, resampling, and software volume are bypassed."
            TransportKind.ANDROID_BIT_PERFECT_MIXER -> "Android bit-perfect USB mixer verified."
            else -> "Bit-perfect output active."
        }
        return BitPerfectVerdict(bitPerfect = true, note = successNote)
    }

    fun isLosslessFormatPreserved(source: PcmFormat, output: PcmFormat): Boolean {
        if (source.sampleRateHz != output.sampleRateHz || source.channels != output.channels) return false
        return when {
            source.encoding == SampleEncoding.DOP_24 && output.encoding == SampleEncoding.DOP_24 ->
                output.wireBitDepth >= 24 && output.containerBits >= 24

            source.encoding == SampleEncoding.DOP_24 || output.encoding == SampleEncoding.DOP_24 ->
                false

            // Direct integer output (identical or zero-padded lossless container widening)
            source.encoding == SampleEncoding.PCM_INT && output.encoding == SampleEncoding.PCM_INT ->
                source.sourceBitDepth > 0 &&
                    output.wireBitDepth >= source.sourceBitDepth &&
                    output.containerBits >= source.sourceBitDepth

            // Float32 transport carrying <= 24-bit integer source scaled by exact power-of-two into integer output
            source.encoding == SampleEncoding.PCM_FLOAT && output.encoding == SampleEncoding.PCM_INT ->
                source.sourceBitDepth in 1..24 &&
                    source.exactPowerOfTwoFloat &&
                    output.wireBitDepth >= source.sourceBitDepth &&
                    output.containerBits >= source.sourceBitDepth

            // Direct float32 -> float32 (only if source itself was float32 or exact <= 24-bit pow2 container)
            source.encoding == SampleEncoding.PCM_FLOAT && output.encoding == SampleEncoding.PCM_FLOAT ->
                source.sourceBitDepth <= 24 || output.wireBitDepth >= 32

            // Int -> Float32 is lossless ONLY if source <= 24-bit with exact pow2 and output itself uses exact pow2 float
            source.encoding == SampleEncoding.PCM_INT && output.encoding == SampleEncoding.PCM_FLOAT ->
                source.sourceBitDepth in 1..24 && output.exactPowerOfTwoFloat

            else -> false
        }
    }

    private fun formatMismatchReason(source: PcmFormat, output: PcmFormat): String = when {
        source.encoding == SampleEncoding.PCM_FLOAT && source.sourceBitDepth > 24 ->
            "Not bit-perfect: >24-bit integer audio through 32-bit float loses precision."
        source.encoding == SampleEncoding.PCM_FLOAT && !source.exactPowerOfTwoFloat ->
            "Not bit-perfect: float conversion did not use exact power-of-two scaling."
        output.wireBitDepth < source.sourceBitDepth ->
            "Not bit-perfect: bit depth truncated (${source.sourceBitDepth}-bit -> ${output.wireBitDepth}-bit)."
        else ->
            "Not bit-perfect: device did not accept the decoded format."
    }
}
