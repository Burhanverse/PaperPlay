package io.max.paperplay.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live spectrum of what is being played, for visuals. The engine feeds PCM in; readers poll [bands].
 * [BANDS] log-spaced bands, each 0..1. [fresh] is false when nothing arrived lately (paused, or the platform has no tap).
 */
object AudioTap {
    const val BANDS = 32
    private const val N = 1024
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val acc = FloatArray(N)
    private var fill = 0
    private var rate = 44100
    private var edges = IntArray(BANDS + 1)
    private var ceiling = 0.25f

    @Volatile var bands = FloatArray(BANDS)
        private set
    @Volatile private var stamp = 0L
    @Volatile private var readAt = 0L

    /** True while a visual has asked for the spectrum in the last two seconds. The audio thread skips all tap work otherwise. */
    val wanted: Boolean get() = System.nanoTime() - readAt < 2_000_000_000L

    private val histBands = Array(96) { FloatArray(BANDS) }
    private val histStamp = LongArray(96)
    @Volatile private var histHead = 0

    /** The spectrum as it was [delayMs] ago, since the sound leaves the speaker a little after it is analysed. */
    fun read(delayMs: Long): FloatArray {
        readAt = System.nanoTime()
        val want = System.nanoTime() - delayMs * 1_000_000L
        val head = histHead
        for (k in 0 until 96) {
            val i = (head - k + 96 * 2) % 96
            if (histStamp[i] in 1..want) return histBands[i]
        }
        return bands
    }

    fun fresh(): Boolean {
        val now = System.nanoTime()
        readAt = now
        return now - stamp < 250_000_000L
    }

    fun reset() { fill = 0; bands = FloatArray(BANDS); stamp = 0L; histStamp.fill(0L) }

    /** One mono sample in -1..1. Called on the audio thread. */
    fun push(sampleRateHz: Int, s: Float) {
        if (sampleRateHz != rate || edges[1] == 0) configure(sampleRateHz)
        acc[fill++] = s
        if (fill < N) return
        // Half overlap keeps the picture moving at ~86 updates a second.
        analyse()
        System.arraycopy(acc, N / 2, acc, 0, N / 2)
        fill = N / 2
    }

    private fun configure(hz: Int) {
        rate = hz
        val bin = hz.toFloat() / N
        val lo = 40f; val hi = minOf(14000f, hz / 2f - 1f)
        var prev = 1
        edges = IntArray(BANDS + 1) { i ->
            val f = lo * ((hi / lo).toDouble().pow(i.toDouble() / BANDS)).toFloat()
            (f / bin).toInt().coerceAtLeast(prev).also { prev = it }
        }
        for (i in 1..BANDS) if (edges[i] <= edges[i - 1]) edges[i] = edges[i - 1] + 1
    }

    private fun analyse() {
        for (i in 0 until N) { re[i] = acc[i] * window[i]; im[i] = 0f }
        fft()
        val out = FloatArray(BANDS)
        var peak = 0f
        for (b in 0 until BANDS) {
            var e = 0f
            val a = edges[b]; val z = minOf(edges[b + 1], N / 2)
            for (k in a until z) e = maxOf(e, sqrt(re[k] * re[k] + im[k] * im[k]))
            // Tilt: higher bands carry less energy in music, lift them so the right side moves too.
            val m = e / (N / 4f) * (1f + 0.8f * b / BANDS)
            out[b] = m
            if (m > peak) peak = m
        }
        // Slow auto gain so quiet and loud masters both fill the range.
        ceiling = if (peak > ceiling) peak else ceiling * 0.9995f + peak * 0.0005f
        val c = maxOf(ceiling, 0.02f)
        for (b in 0 until BANDS) {
            val db = 20f * log10(maxOf(out[b], 1e-5f) / c)
            // A curve so only real peaks reach the top and the quiet bands stay low.
            val x = ((db + 36f) / 36f).coerceIn(0f, 1f)
            out[b] = x * x
        }
        val now = System.nanoTime()
        val slot = (histHead + 1) % 96
        histBands[slot] = out; histStamp[slot] = now; histHead = slot
        bands = out
        stamp = now
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= N) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
