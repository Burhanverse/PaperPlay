package io.max.paperplay.player

import kotlin.math.abs
import kotlin.math.exp

/**
 * Zero-allocation half-wave rectified spectral flux onset detector.
 *
 * Weighs bass and low-mid bands (0..7) more heavily so kick drums and bass hits trigger [beatHit]
 * and drive [onset] without false triggers on silence or steady sustained tones.
 */
class SpectralOnsetDetector(private val bandsCount: Int = AudioTap.BANDS) {
    private val prev = FloatArray(bandsCount)
    private var fluxMean = 0f
    private var fluxDev = 0f
    private var cooldown = 0f

    /** Decaying onset envelope in `0f..1f`. */
    var onset: Float = 0f
        private set

    /** True only on the frame an onset fires. */
    var beatHit: Boolean = false
        private set

    fun update(bands: FloatArray, dt: Float) {
        val safeDt = dt.coerceIn(0.001f, 0.1f)
        if (cooldown > 0f) cooldown = (cooldown - safeDt).coerceAtLeast(0f)

        var flux = 0f
        var weightSum = 0f
        val limit = minOf(bands.size, bandsCount)
        for (i in 0 until limit) {
            val cur = bands[i]
            val diff = cur - prev[i]
            prev[i] = cur
            val w = if (i < 8) 2.5f else 1f
            weightSum += w
            if (diff > 0f) flux += diff * w
        }
        if (weightSum > 0f) flux /= weightSum

        val alpha = 1f - exp(-safeDt / 0.35f)
        fluxMean += (flux - fluxMean) * alpha
        fluxDev += (abs(flux - fluxMean) - fluxDev) * alpha

        val threshold = fluxMean + 1.5f * fluxDev
        if (flux > threshold && flux > 0.03f && cooldown <= 0f) {
            beatHit = true
            onset = ((flux - threshold) * 4f + 0.55f).coerceIn(0.45f, 1f)
            cooldown = 0.12f
        } else {
            beatHit = false
            onset *= exp(-safeDt / 0.14f)
            if (onset < 0.001f) onset = 0f
        }
    }

    fun reset() {
        prev.fill(0f)
        fluxMean = 0f
        fluxDev = 0f
        cooldown = 0f
        onset = 0f
        beatHit = false
    }
}
