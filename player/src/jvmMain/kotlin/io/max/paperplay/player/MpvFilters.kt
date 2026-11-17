package io.max.paperplay.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer, bass and correction profile as an mpv audio filter graph. It follows the Android engine (`engine/src/eq.rs`):
 * the band gains are fitted so the combined response passes through the drawn curve, a bass stage sits on top, and after the
 * equalizer the sound is split at 180 Hz so each half gets its own level trim and look-ahead limiter. Bass peaks are limited
 * in the bass half alone and cannot duck the mids and highs. The fit is made at 48 kHz; mpv sets the filter coefficients for the
 * real rate of each file.
 */
internal object MpvFilters {
    val BANDS_HZ = doubleArrayOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private const val N = 10
    private const val Q = 1.41
    private const val RATE = 48000.0
    private const val MAX_FIT_DB = 18.0
    private const val BASS_NET_DB = 4.0
    private const val SEPARATION = 0.7
    private const val MAX_REST_CUT_DB = 10.0
    private const val HIGH_SHARE = 0.35
    private const val HIGH_FROM_HZ = 400.0
    private const val CROSSOVER_HZ = 180.0
    private const val LIMIT_LOW = 0.8
    private const val LIMIT_HIGH = 0.7
    private const val LIMIT_OUT = 0.98
    private const val RELEASE_MS = 120

    private class Coef(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private fun peaking(hz: Double, gainDb: Double): Coef {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * hz / RATE
        val alpha = sin(w) / (2 * Q)
        val cw = cos(w)
        val a0 = 1 + alpha / a
        return Coef((1 + alpha * a) / a0, -2 * cw / a0, (1 - alpha * a) / a0, -2 * cw / a0, (1 - alpha / a) / a0)
    }

    /** RBJ low shelf, slope 1. */
    private fun lowShelf(hz: Double, gainDb: Double): Coef {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * hz / RATE
        val sw = sin(w); val cw = cos(w)
        val alpha = sw / 2 * sqrt(2.0)
        val t = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cw + t
        return Coef(
            a * ((a + 1) - (a - 1) * cw + t) / a0,
            2 * a * ((a - 1) - (a + 1) * cw) / a0,
            a * ((a + 1) - (a - 1) * cw - t) / a0,
            -2 * ((a - 1) + (a + 1) * cw) / a0,
            ((a + 1) + (a - 1) * cw - t) / a0,
        )
    }

    private fun highPass(hz: Double): Coef {
        val w = 2 * PI * hz / RATE
        val alpha = sin(w) / (2 * sqrt(0.5))
        val cw = cos(w)
        val a0 = 1 + alpha
        return Coef((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
    }

    private fun magDb(k: Coef, hz: Double): Double {
        val w = 2 * PI * hz / RATE
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = k.b0 + k.b1 * c1 + k.b2 * c2
        val ni = -(k.b1 * s1 + k.b2 * s2)
        val dr = 1 + k.a1 * c1 + k.a2 * c2
        val di = -(k.a1 * s1 + k.a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** Solves m x = b by Gaussian elimination with partial pivoting; null when singular. */
    private fun solve(mIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray? {
        val m = Array(N) { mIn[it].copyOf() }
        val b = bIn.copyOf()
        for (c in 0 until N) {
            var p = c
            for (r in c + 1 until N) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-9) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            val tb = b[c]; b[c] = b[p]; b[p] = tb
            for (r in c + 1 until N) {
                val f = m[r][c] / m[c][c]
                for (k in c until N) m[r][k] -= f * m[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(N)
        for (r in N - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until N) s -= m[r][k] * x[k]
            x[r] = s / m[r][r]
        }
        return x
    }

    /** What the curve asks of the signal: the fitted band gains and the level trims of the two halves. */
    class Plan(
        val bands: DoubleArray,
        val bassDb: Double,
        val lowTrimDb: Double,
        val highTrimDb: Double,
        val peakDb: Double,
        private val bassCoefs: List<Any>,
        private val bandCoefs: List<Any?>,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun responseDb(hz: Double): Double {
            var sum = 0.0
            for (i in 0 until N) (bandCoefs[i] as Coef?)?.let { sum += magDb(it, hz) }
            if (bassDb >= 0.05) for (k in bassCoefs) sum += magDb(k as Coef, hz)
            return sum
        }
    }

    /** Null when nothing needs doing (flat curve, no bass). */
    fun plan(target: List<Float>?, bassDb: Float): Plan? {
        val flat = target == null || target.size != N || target.all { abs(it) < 0.05f }
        val bass = bassDb.toDouble().coerceIn(0.0, 12.0)
        if (flat && bass < 0.05) return null
        val bands = DoubleArray(N)
        val coefs = arrayOfNulls<Coef>(N)
        if (!flat) {
            val t = DoubleArray(N) { target!![it].toDouble() }
            val usable = BooleanArray(N) { BANDS_HZ[it] < RATE * 0.45 }
            val m = Array(N) { DoubleArray(N) }
            for (j in 0 until N) {
                val k = peaking(BANDS_HZ[j], 6.0)
                for (i in 0 until N) m[i][j] = if (usable[j]) magDb(k, BANDS_HZ[i]) / 6.0 else 0.0
                if (!usable[j]) m[j][j] = 1.0
            }
            val g = (solve(m, t) ?: t).copyOf()
            for (i in 0 until N) g[i] = g[i].coerceIn(-MAX_FIT_DB, MAX_FIT_DB)
            repeat(2) {
                val c = Array(N) { peaking(BANDS_HZ[it], g[it]) }
                val err = DoubleArray(N) { i ->
                    var got = 0.0
                    for (j in 0 until N) if (usable[j] && abs(g[j]) >= 0.05) got += magDb(c[j], BANDS_HZ[i])
                    t[i] - got
                }
                solve(m, err)?.let { d -> for (i in 0 until N) g[i] = (g[i] + d[i]).coerceIn(-MAX_FIT_DB, MAX_FIT_DB) }
            }
            for (i in 0 until N) if (usable[i] && abs(g[i]) >= 0.05) { bands[i] = g[i]; coefs[i] = peaking(BANDS_HZ[i], g[i]) }
        }
        val bassCoefs = if (bass >= 0.05) listOf(lowShelf(100.0, bass), peaking(45.0, 0.4 * bass), highPass(22.0)) else emptyList()
        val tmp = Plan(bands, bass, 0.0, 0.0, 0.0, bassCoefs, coefs.toList())
        // Peak of the response below and above the split, separately: each half is trimmed only for its own boost.
        var pl = 0.0; var ph = 0.0
        var f = 20.0
        while (f < min(RATE * 0.45, 20000.0)) {
            val r = tmp.responseDb(f)
            if (f < HIGH_FROM_HZ) pl = max(pl, r) else ph = max(ph, r)
            f *= 1.04
        }
        val over = max(pl - BASS_NET_DB, 0.0)
        val low = -over
        val high = -(min(SEPARATION * over, MAX_REST_CUT_DB) + HIGH_SHARE * ph)
        return Plan(bands, bass, low, high, max(pl, ph), bassCoefs, coefs.toList())
    }

    private fun num(v: Double) = "%.3f".format(java.util.Locale.ROOT, v)
    private fun limiter(limit: Double) = "alimiter=limit=$limit:attack=1:release=$RELEASE_MS:level=0"

    /** The graph as one filter chain (no labels at its ends), or null when the plan is empty. */
    fun graph(plan: Plan?, normalize: Boolean, profile: List<Pair<Double, Double>>?): String? {
        val parts = ArrayList<String>()
        if (normalize) parts.add("dynaudnorm=f=500:g=15:p=0.95")
        if (profile != null && profile.size >= 2) parts.add("firequalizer=gain_entry='" + profile.joinToString(";") { "entry(${num(it.first)},${num(it.second)})" } + "'")
        if (plan != null) {
            for (i in 0 until N) if (abs(plan.bands[i]) >= 0.05) parts.add("equalizer=f=${num(BANDS_HZ[i])}:t=q:w=$Q:g=${num(plan.bands[i])}")
            if (plan.bassDb >= 0.05) {
                parts.add("bass=g=${num(plan.bassDb)}:f=100:t=s:w=1")
                parts.add("equalizer=f=45:t=q:w=$Q:g=${num(0.4 * plan.bassDb)}")
                parts.add("highpass=f=22:poles=2")
            }
        }
        if (parts.isEmpty()) return null
        val chain = parts.joinToString(",")
        if (plan == null) return chain
        // The rest was lowered to keep the bass clear; give back a quarter of that (at most 2 dB) on the high half only, so bass peaks are not squeezed.
        val highDb = plan.highTrimDb + (-plan.highTrimDb / 4.0).coerceIn(0.0, 2.0)
        return chain + ",acrossover=split=${CROSSOVER_HZ.toInt()}:order=4th[lo][hi];" +
            "[lo]volume=${num(plan.lowTrimDb)}dB,${limiter(LIMIT_LOW)}[l2];" +
            "[hi]volume=${num(highDb)}dB,${limiter(LIMIT_HIGH)}[h2];" +
            "[l2][h2]amix=inputs=2:normalize=0,${limiter(LIMIT_OUT)}"
    }

    /** mpv option value for `af`: the graph, quoted so that nothing in it is read as mpv syntax. */
    fun afValue(graph: String?): String = if (graph == null) "" else "lavfi=graph=%${graph.toByteArray(Charsets.UTF_8).size}%$graph"
}
