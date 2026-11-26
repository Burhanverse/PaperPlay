package io.max.paperplay.player

import kotlin.math.abs
import kotlin.math.exp

/**
 * Zero-allocation half-wave rectified spectral flux + time-domain bass onset detector.
 *
 * Can be driven at a fixed analysis hop rate via [updateHop] inside [AudioTap], or at display
 * frame rate via [update] for standalone tests. Weighs kick/bass and snare/low-mid bands more
 * heavily so rhythmic hits trigger [beatHit] cleanly without false positives on silence or
 * steady sustained tones.
 */
class SpectralOnsetDetector(private val bandsCount: Int = AudioTap.BANDS) {
    private val prev = FloatArray(bandsCount)
    private var prevBass = 0f
    private var fluxMean = 0f
    private var fluxDev = 0f
    private var cooldown = 0f

    /** Decaying onset envelope in `0f..1f`. */
    var onset: Float = 0f
        private set

    /** True only on the frame/hop an onset fires. */
    var beatHit: Boolean = false
        private set

    fun update(bands: FloatArray, dt: Float) {
        updateHop(bands, bassEnv = 0f, hopDtSec = dt)
    }

    /**
     * Advances the onset detector by one analysis step of duration [hopDtSec].
     * Returns true when a beat onset triggers on this hop.
     */
    fun updateHop(bands: FloatArray, bassEnv: Float, hopDtSec: Float): Boolean {
        val safeDt = hopDtSec.coerceIn(0.001f, 0.1f)
        if (cooldown > 0f) cooldown = (cooldown - safeDt).coerceAtLeast(0f)

        var flux = 0f
        var weightSum = 0f
        val limit = minOf(bands.size, bandsCount)
        for (i in 0 until limit) {
            val cur = bands[i]
            val diff = cur - prev[i]
            prev[i] = cur
            val w = when {
                i < 8 -> 2.4f
                i < 16 -> 1.4f
                i < 24 -> 1.0f
                else -> 0.5f
            }
            weightSum += w
            if (diff > 0f) flux += diff * w
        }
        if (weightSum > 0f) flux /= weightSum

        val bassDiff = (bassEnv - prevBass).coerceAtLeast(0f)
        prevBass = bassEnv
        val combinedFlux = flux + bassDiff * 0.45f

        val alpha = 1f - exp(-safeDt / 0.40f)
        fluxMean += (combinedFlux - fluxMean) * alpha
        fluxDev += (abs(combinedFlux - fluxMean) - fluxDev) * alpha

        val threshold = fluxMean + 1.65f * fluxDev
        if (combinedFlux > threshold && combinedFlux > 0.025f && cooldown <= 0f) {
            beatHit = true
            onset = ((combinedFlux - threshold) * 4.5f + 0.55f).coerceIn(0.45f, 1f)
            cooldown = 0.13f
        } else {
            beatHit = false
            onset *= exp(-safeDt / 0.11f)
            if (onset < 0.001f) onset = 0f
        }
        return beatHit
    }

    fun reset() {
        prev.fill(0f)
        prevBass = 0f
        fluxMean = 0f
        fluxDev = 0f
        cooldown = 0f
        onset = 0f
        beatHit = false
    }
}
