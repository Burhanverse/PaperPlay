package io.max.paperplay.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live spectrum of what is being played, for visuals.
 *
 * Audio paths feed PCM via [push], [pushWithPts], or [pushMonoInt16]; readers poll [sample], [fresh], or [read].
 * [BANDS] log-spaced bands, each 0..1. [sample] and [fresh] return false when paused, idle, DoP/DSD, or stale.
 * All audio-thread methods are lock-free and zero-allocation.
 */
object AudioTap {
    const val BANDS = 32
    private const val N = 1024
    private const val HOP = N / 2
    private const val HIST_SIZE = 192
    private const val DEFAULT_LATENCY_MS = 80L
    private const val MAX_RATE_BEFORE_DECIM = 56_000

    /** Injectable monotonic clock in nanoseconds; overridden in unit tests for deterministic timing. */
    @Volatile
    internal var clockNs: () -> Long = { System.nanoTime() }

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val acc = FloatArray(N)
    private val tmpBands = FloatArray(BANDS)
    private val latestBands = FloatArray(BANDS)
    private val readScratch = FloatArray(BANDS)
    private var fill = 0
    private var rate = 44100
    private var effRate = 44100
    private var decim = 1
    private var decimSum = 0f
    private var decimCount = 0
    private val edges = IntArray(BANDS + 1)
    private var ceiling = 0.25f

    /** Latest computed spectrum (0..1 per band). Prefer [sample] for time-aligned draw-phase reads. */
    @Volatile
    var bands = FloatArray(BANDS)
        private set

    @Volatile private var readAt = 0L
    @Volatile private var hasRead = false
    @Volatile private var hasData = false
    @Volatile private var paused = false
    @Volatile private var nonPcmActive = false

    @Volatile private var latestPlayoutNs = 0L
    @Volatile private var lastPushWallNs = 0L
    @Volatile private var anchorWallNs = 0L
    @Volatile private var anchorPosUs = -1L
    @Volatile private var currentSpeed = 1f
    @Volatile private var reportedLatencyNs = DEFAULT_LATENCY_MS * 1_000_000L
    @Volatile private var nextStreamPlayoutNs = 0L

    /** User or platform calibration offset in ms (positive shifts visual earlier, negative delays it). */
    @Volatile var calibrationOffsetMs: Long = 0L

    /** Last measured output latency in ms between PCM tap and audible playout. */
    @Volatile var measuredLatencyMs: Long = 0L
        private set

    /** Diagnostic tag of the most recent active audio feed path. */
    @Volatile var activeSourceTag: String = ""
        private set

    /** Diagnostic counter of mono samples pushed since reset/start. */
    @Volatile var pushSampleCount: Long = 0L
        private set

    /** Diagnostic counter of FFT hops analysed since reset/start. */
    @Volatile var fftFrameCount: Long = 0L
        private set

    /** True while a visual has asked for the spectrum in the last two seconds. The audio thread skips all tap work otherwise. */
    val wanted: Boolean
        get() = hasRead && (clockNs() - readAt < 2_000_000_000L)

    private val histBands = Array(HIST_SIZE) { FloatArray(BANDS) }
    private val histPlayoutNs = LongArray(HIST_SIZE)
    private val histSeq = LongArray(HIST_SIZE)
    @Volatile private var histHead = 0

    /** Updates the audible playback clock anchor from the active engine/sink. Lock-free. */
    fun updatePlaybackClock(audiblePosUs: Long, speed: Float = currentSpeed, nowNs: Long = clockNs()) {
        if (audiblePosUs < 0L) return
        anchorPosUs = audiblePosUs
        anchorWallNs = nowNs
        currentSpeed = speed.coerceIn(0.25f, 4f)
    }

    /** Sets the engine-reported output latency in milliseconds (clamped 0..2000 ms). */
    fun setReportedLatencyMs(latencyMs: Long) {
        reportedLatencyNs = latencyMs.coerceIn(0L, 2_000L) * 1_000_000L
    }

    /** Updates playback speed for hop-to-hop playout scheduling. */
    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.25f, 4f)
    }

    /** Marks playback paused and clears stale frames so visuals settle immediately. */
    fun onPause() {
        paused = true
        hasData = false
        latestPlayoutNs = 0L
        nextStreamPlayoutNs = 0L
        anchorWallNs = 0L
        anchorPosUs = -1L
        latestBands.fill(0f)
        bands.fill(0f)
        histPlayoutNs.fill(0L)
    }

    /** Marks a non-PCM stream (such as DoP/DSD) active so visuals stay in a clean idle state. */
    fun markNonPcmStream() {
        nonPcmActive = true
        hasData = false
        latestPlayoutNs = 0L
        nextStreamPlayoutNs = 0L
        latestBands.fill(0f)
        bands.fill(0f)
        histPlayoutNs.fill(0L)
    }

    /**
     * Copies the time-aligned spectrum into [out] (length >= [BANDS]).
     * Returns true if live, non-stale, non-paused PCM spectrum data is available.
     */
    fun sample(out: FloatArray, nowNs: Long = clockNs()): Boolean {
        readAt = nowNs
        hasRead = true
        if (paused || nonPcmActive || !hasData) return false
        if (nowNs > latestPlayoutNs + 250_000_000L) return false
        if (nowNs - lastPushWallNs > 1_500_000_000L) return false

        val targetNs = nowNs + calibrationOffsetMs * 1_000_000L
        val head = histHead
        var bestIdx = -1
        var bestPlayout = Long.MIN_VALUE
        var earliestFutureIdx = -1
        var earliestFuturePlayout = Long.MAX_VALUE

        for (k in 0 until HIST_SIZE) {
            val i = (head - k + HIST_SIZE) % HIST_SIZE
            val ts = histPlayoutNs[i]
            if (ts <= 0L) continue
            if (ts <= targetNs) {
                if (ts > bestPlayout) {
                    bestPlayout = ts
                    bestIdx = i
                }
            } else if (ts < earliestFuturePlayout) {
                earliestFuturePlayout = ts
                earliestFutureIdx = i
            }
        }

        val chosen = when {
            bestIdx >= 0 -> bestIdx
            earliestFutureIdx >= 0 && earliestFuturePlayout - targetNs <= 500_000_000L -> earliestFutureIdx
            else -> -1
        }
        if (chosen < 0) return false

        val copyLen = minOf(out.size, BANDS)
        for (attempt in 0 until 3) {
            val s1 = histSeq[chosen]
            if ((s1 and 1L) != 0L) continue
            System.arraycopy(histBands[chosen], 0, out, 0, copyLen)
            val s2 = histSeq[chosen]
            if (s1 == s2) return true
        }
        System.arraycopy(latestBands, 0, out, 0, copyLen)
        return true
    }

    /** The spectrum as it was [delayMs] ago, since the sound leaves the speaker a little after it is analysed. */
    fun read(delayMs: Long): FloatArray {
        val now = clockNs()
        readAt = now
        hasRead = true
        val want = now - delayMs * 1_000_000L
        val head = histHead
        var bestIdx = -1
        var bestTs = Long.MIN_VALUE
        for (k in 0 until HIST_SIZE) {
            val i = (head - k + HIST_SIZE) % HIST_SIZE
            val ts = histPlayoutNs[i]
            if (ts in 1L..want && ts > bestTs) {
                bestTs = ts
                bestIdx = i
            }
        }
        if (bestIdx >= 0) {
            System.arraycopy(histBands[bestIdx], 0, readScratch, 0, BANDS)
            return readScratch
        }
        System.arraycopy(latestBands, 0, readScratch, 0, BANDS)
        return readScratch
    }

    fun fresh(): Boolean {
        val now = clockNs()
        readAt = now
        hasRead = true
        if (paused || nonPcmActive || !hasData) return false
        if (now > latestPlayoutNs + 250_000_000L) return false
        return now - lastPushWallNs <= 1_500_000_000L
    }

    fun reset() {
        fill = 0
        decimSum = 0f
        decimCount = 0
        ceiling = 0.25f
        hasData = false
        paused = false
        nonPcmActive = false
        latestPlayoutNs = 0L
        lastPushWallNs = 0L
        anchorWallNs = 0L
        anchorPosUs = -1L
        nextStreamPlayoutNs = 0L
        histHead = 0
        histPlayoutNs.fill(0L)
        histSeq.fill(0L)
        latestBands.fill(0f)
        bands.fill(0f)
        readScratch.fill(0f)
        pushSampleCount = 0L
        fftFrameCount = 0L
        measuredLatencyMs = 0L
    }

    /** One mono sample in -1..1. Called on the audio thread. */
    fun push(sampleRateHz: Int, s: Float) {
        pushWithPts(sampleRateHz, s, -1L, activeSourceTag)
    }

    /** One mono sample in -1..1 with an optional stream PTS in microseconds. Lock-free, zero-allocation. */
    fun pushWithPts(sampleRateHz: Int, s: Float, ptsUs: Long, sourceTag: String = "") {
        if (sampleRateHz <= 0) return
        if (sampleRateHz != rate || edges[1] == 0) configure(sampleRateHz)
        if (sourceTag.isNotEmpty() && activeSourceTag != sourceTag) activeSourceTag = sourceTag
        paused = false
        nonPcmActive = false
        pushSampleCount++

        if (decim == 1) {
            acc[fill++] = s
        } else {
            decimSum += s
            decimCount++
            if (decimCount < decim) return
            acc[fill++] = decimSum / decimCount
            decimSum = 0f
            decimCount = 0
        }

        if (fill < N) return
        analyse(ptsUs)
        System.arraycopy(acc, HOP, acc, 0, HOP)
        fill = HOP
    }

    /** Feeds a batch of 16-bit signed mono samples with an optional starting stream PTS in microseconds. */
    fun pushMonoInt16(sampleRateHz: Int, buf: ShortArray, count: Int, ptsUs: Long = -1L, sourceTag: String = "") {
        if (sampleRateHz <= 0 || count <= 0) return
        val limit = minOf(count, buf.size)
        val hasPts = ptsUs >= 0L
        for (i in 0 until limit) {
            val samplePts = if (hasPts) ptsUs + (i.toLong() * 1_000_000L) / sampleRateHz else -1L
            pushWithPts(sampleRateHz, buf[i] / 32768f, samplePts, sourceTag)
        }
    }

    private fun configure(hz: Int) {
        rate = hz
        var d = 1
        while (hz / d > MAX_RATE_BEFORE_DECIM) d *= 2
        decim = d
        effRate = (hz / d).coerceAtLeast(1)
        decimSum = 0f
        decimCount = 0
        fill = 0

        val bin = effRate.toFloat() / N
        val lo = 40f
        val hi = minOf(14000f, effRate / 2f - 1f).coerceAtLeast(lo + 100f)
        var prev = 1
        for (i in 0..BANDS) {
            val f = lo * ((hi / lo).toDouble().pow(i.toDouble() / BANDS)).toFloat()
            val edge = (f / bin).toInt().coerceAtLeast(prev)
            edges[i] = edge
            prev = edge
        }
        for (i in 1..BANDS) {
            if (edges[i] <= edges[i - 1]) edges[i] = edges[i - 1] + 1
        }
    }

    private fun analyse(ptsUs: Long) {
        for (i in 0 until N) {
            re[i] = acc[i] * window[i]
            im[i] = 0f
        }
        fft()

        var peak = 0f
        var maxRaw = 0f
        for (b in 0 until BANDS) {
            var e = 0f
            val a = edges[b]
            val z = minOf(edges[b + 1], N / 2)
            for (k in a until z) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                if (mag > e) e = mag
            }
            // Tilt: higher bands carry less energy in music, lift them so the right side moves too.
            val m = e / (N / 4f) * (1f + 0.8f * b / BANDS)
            tmpBands[b] = m
            if (m > peak) peak = m
            if (e / (N / 4f) > maxRaw) maxRaw = e / (N / 4f)
        }

        // Slow auto gain so quiet and loud masters both fill the range, with a -80 dBFS silence floor.
        ceiling = if (peak > ceiling) peak else ceiling * 0.9995f + peak * 0.0005f
        val c = maxOf(ceiling, 0.04f)
        for (b in 0 until BANDS) {
            if (tmpBands[b] < 1e-4f) {
                tmpBands[b] = 0f
            } else {
                val db = 20f * log10(maxOf(tmpBands[b], 1e-5f) / c)
                // A curve so only real peaks reach the top and the quiet bands stay low.
                val x = ((db + 36f) / 36f).coerceIn(0f, 1f)
                tmpBands[b] = x * x
            }
        }

        val now = clockNs()
        lastPushWallNs = now
        val playoutNs = computePlayoutNs(now, ptsUs)
        latestPlayoutNs = playoutNs
        measuredLatencyMs = ((playoutNs - now) / 1_000_000L).coerceIn(0L, 2_000L)

        val slot = (histHead + 1) % HIST_SIZE
        val seq = histSeq[slot]
        histSeq[slot] = seq + 1L // odd = writing
        System.arraycopy(tmpBands, 0, histBands[slot], 0, BANDS)
        histPlayoutNs[slot] = playoutNs
        histSeq[slot] = seq + 2L // even = stable
        histHead = slot

        System.arraycopy(tmpBands, 0, latestBands, 0, BANDS)
        System.arraycopy(tmpBands, 0, bands, 0, BANDS)
        hasData = true
        fftFrameCount++
    }

    private fun computePlayoutNs(nowNs: Long, ptsUs: Long): Long {
        val anchorWall = anchorWallNs
        val anchorPos = anchorPosUs
        if (ptsUs >= 0L && anchorWall > 0L && anchorPos >= 0L && nowNs - anchorWall < 2_000_000_000L) {
            val centerOffsetUs = (HOP.toLong() * 1_000_000L) / effRate
            val hopPtsUs = (ptsUs - centerOffsetUs).coerceAtLeast(0L)
            val deltaNs = ((hopPtsUs - anchorPos) * 1_000.0 / currentSpeed).toLong()
            val clampedDelta = deltaNs.coerceIn(-500_000_000L, 2_500_000_000L)
            val scheduled = anchorWall + clampedDelta
            nextStreamPlayoutNs = scheduled
            return scheduled
        }

        val hopNs = (HOP.toLong() * 1_000_000_000.0 / (effRate.toDouble() * currentSpeed)).toLong().coerceAtLeast(1_000_000L)
        val lat = reportedLatencyNs
        var next = nextStreamPlayoutNs
        if (next == 0L || next < nowNs - 80_000_000L || next > nowNs + lat + 500_000_000L) {
            next = nowNs + lat
        } else {
            next += hopNs
        }
        nextStreamPlayoutNs = next
        return next
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
