package io.max.paperplay.player.usb

import kotlin.math.roundToInt

/**
 * Pure, cross-platform hardware volume curve calculator for USB Audio (UAC 1.0 & UAC 2.0).
 *
 * Implements a single fixed perceptual loudness curve from system volume index (0..maxIndex)
 * to DAC attenuation in dB. Bypasses Android's non-linear `getStreamVolumeDb` table to provide
 * predictable, safe fader response.
 *
 * Requirements:
 * 1. Endpoints: index 0 = MUTE (Float.NEGATIVE_INFINITY), index maxIndex = 0 dB (clamped to dacMaxDb).
 * 2. Monotonicity: for all indices i >= 1, db(i + 1) > db(i) (subject to DAC resolution quantization).
 * 3. Step size: average step size <= ~3.5 dB per step across standard DACs.
 * 4. Floor: near -50 to -60 dB (adaptive to maxIndex).
 * 5. Hardware constraints: clamped to DAC minDb/maxDb/resDb.
 * 6. Safety: slew limiter ensures non-user changes can never raise the level by more than one step.
 */
object UsbVolumeCurve {

    /**
     * Computes the target attenuation in dB for a given system volume [index].
     *
     * @param index System volume index in 0..maxIndex.
     * @param maxIndex Maximum system volume index (typically 15, 16, or 25).
     * @param dacMinDb Minimum hardware volume supported by DAC in dB (e.g. -74.0f).
     * @param dacMaxDb Maximum hardware volume supported by DAC in dB (e.g. 0.0f).
     * @param dacResDb Step resolution supported by DAC in dB (e.g. 0.5f).
     * @return Hardware volume in dB, or [Float.NEGATIVE_INFINITY] if muted (index == 0).
     */
    fun computeVolumeDb(
        index: Int,
        maxIndex: Int,
        dacMinDb: Float,
        dacMaxDb: Float,
        dacResDb: Float
    ): Float {
        if (index <= 0 || maxIndex <= 0) {
            return Float.NEGATIVE_INFINITY
        }

        val effectiveMaxDb = minOf(0.0f, dacMaxDb)
        if (index >= maxIndex) {
            return effectiveMaxDb
        }

        // Target span capped at 60 dB or (maxIndex - 1) * 3.5 dB, floor clamped to dacMinDb
        val totalSteps = maxIndex - 1
        val targetSpan = minOf(60.0f, totalSteps * 3.5f)
        val floorDb = maxOf(dacMinDb, effectiveMaxDb - targetSpan)

        val ratio = (index - 1).toFloat() / totalSteps.toFloat()
        val rawDb = floorDb + (effectiveMaxDb - floorDb) * ratio

        // Quantize to DAC resolution
        val res = if (dacResDb > 0.001f) dacResDb else 0.5f
        val steps = ((rawDb - dacMinDb) / res).roundToInt()
        val quantized = dacMinDb + steps * res

        return quantized.coerceIn(dacMinDb, effectiveMaxDb)
    }

    /**
     * Converts a volume in dB to UAC 1.0 / 2.0 raw short (units of 1/256 dB).
     */
    fun dbToRaw(
        db: Float,
        minRaw: Short,
        maxRaw: Short,
        resRaw: Short
    ): Short {
        if (db.isInfinite() && db < 0f) {
            return minRaw
        }
        val effectiveMaxRaw = maxRaw.toInt().coerceAtMost(0).toShort()
        val step = resRaw.toInt().coerceAtLeast(1)
        val raw = (db * 256.0f).roundToInt()
        val quantized = (minRaw.toInt() + (((raw - minRaw.toInt()).toFloat() / step).roundToInt() * step))
        return quantized.coerceIn(minRaw.toInt(), effectiveMaxRaw.toInt()).toShort()
    }

    /**
     * Safety slew limiter for system volume indices.
     * An automatic (non-user) increase of more than one curve step is clamped to
     * [lastAppliedIndex] + 1 step. User actions apply as-is without slew limiting.
     * Decreases in volume apply immediately for both user and automatic callers for safety.
     */
    fun applySlewLimiter(
        requestedIndex: Int,
        lastAppliedIndex: Int?,
        maxIndex: Int,
        isUser: Boolean
    ): Int {
        if (isUser || lastAppliedIndex == null || requestedIndex <= lastAppliedIndex + 1) {
            return requestedIndex.coerceIn(0, maxIndex)
        }
        return (lastAppliedIndex + 1).coerceAtMost(maxIndex)
    }
}
