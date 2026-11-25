package io.max.paperplay.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Live multi-resolution spectrum, time-domain bass envelope, and onset analyzer for visuals.
 *
 * Audio paths feed PCM via [push], [pushWithPts], or [pushMonoInt16]; readers poll [sampleFrame],
 * [sample], [fresh], or [read].
 *
 * Design:
 * - Circular sample buffer with fixed [HOP] = 512 samples at the decimated rate (<= 56 kHz).
 * - Dual-resolution FFT: 4096-point Blackman-Harris for bands <= 550 Hz (~10.8 Hz bin spacing)
 *   and 1024-point Hann for bands > 550 Hz (~43.1 Hz bin spacing, crisp transient response).
 * - Exact-Hz 32-band triangular filterbank (42 Hz..14.5 kHz) with RMS power aggregation and
 *   perceptual high-frequency tilt, invariant across 44.1..384 kHz sample rates.
 * - Separate 140 Hz 2nd-order Butterworth time-domain bass envelope for beat/kick punch.
 * - Per-region running AGC (25 ms attack, 1.6 s release) + 48 dB log compression + -67 dBFS gate.
 * - Fixed-hop [SpectralOnsetDetector] + seqlock playout ring with linear display-rate interpolation.
 *
 * All audio-thread methods are lock-free, panic-free, and strictly zero-allocation.
 */
object AudioTap {
    const val BANDS = 32
    private const val N_LONG = 4096
    private const val N_SHORT = 1024
    private const val HOP = 512
    private const val HIST_SIZE = 256
    private const val DEFAULT_LATENCY_MS = 80L
    private const val MAX_RATE_BEFORE_DECIM = 56_000

    private const val MIN_HZ = 42.0
    private const val MAX_HZ = 14_500.0
    private const val LOW_SPLIT_HZ = 550.0f
    private const val BASS_LPF_HZ = 140.0
    private const val SILENCE_FLOOR = 0.00045f
    private const val REF_MIN = 0.0018f
    private const val DB_WINDOW = 48f

    /** Display-side snapshot populated by [sampleFrame] without allocations. */
    class TapFrame(val bands: FloatArray = FloatArray(BANDS)) {
        var bass: Float = 0f
        var onset: Float = 0f
        var beatSeq: Int = 0
        var analysisUs: Int = 0
        var effRateHz: Int = 44100
    }

    private val defaultClock: () -> Long = { System.nanoTime() }

    /** Injectable monotonic clock in nanoseconds; overridden in unit tests for deterministic timing. */
    @Volatile
    var clockNs: () -> Long = defaultClock

    private inline fun nowClockNs(): Long = if (clockNs === defaultClock) System.nanoTime() else clockNs()

    private val windowLong = FloatArray(N_LONG)
    private val windowShort = FloatArray(N_SHORT)
    private val winLongNorm: Float
    private val winShortNorm: Float

    private val bitRevLong = buildBitRev(N_LONG)
    private val bitRevShort = buildBitRev(N_SHORT)
    private val twCosLong = FloatArray(N_LONG / 2)
    private val twSinLong = FloatArray(N_LONG / 2)
    private val twCosShort = FloatArray(N_SHORT / 2)
    private val twSinShort = FloatArray(N_SHORT / 2)

    init {
        var sumL = 0.0
        for (i in 0 until N_LONG) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (N_LONG - 1))
            windowLong[i] = w.toFloat()
            sumL += w
        }
        winLongNorm = (4.0 / (sumL * sumL)).toFloat()

        var sumS = 0.0
        for (i in 0 until N_SHORT) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (N_SHORT - 1))
            windowShort[i] = w.toFloat()
            sumS += w
        }
        winShortNorm = (4.0 / (sumS * sumS)).toFloat()

        fillTwiddles(N_LONG, twCosLong, twSinLong)
        fillTwiddles(N_SHORT, twCosShort, twSinShort)
    }

    private fun buildBitRev(n: Int): IntArray {
        val out = IntArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            out[i] = j
        }
        return out
    }

    private fun fillTwiddles(n: Int, cosOut: FloatArray, sinOut: FloatArray) {
        val half = n / 2
        for (k in 0 until half) {
            val ang = -2.0 * PI * k / n
            cosOut[k] = cos(ang).toFloat()
            sinOut[k] = sin(ang).toFloat()
        }
    }

    private val bandCenters = FloatArray(BANDS) { b ->
        (MIN_HZ * (MAX_HZ / MIN_HZ).pow(b.toDouble() / (BANDS - 1))).toFloat()
    }
    private val bandTilt = FloatArray(BANDS) { b ->
        val f = bandCenters[b]
        when {
            f < 80f -> 0.85f + 0.15f * ((f - 42f) / 38f).coerceIn(0f, 1f)
            f <= 1000f -> 1.0f
            else -> {
                val u = (log10(f / 1000f) / log10(14.5f)).coerceIn(0f, 1f)
                1.0f + 0.65f * u
            }
        }
    }

    private val ring = FloatArray(N_LONG)
    private var ringPos = 0
    private var samplesSinceHop = 0
    private var totalEffSamples = 0

    private val reLong = FloatArray(N_LONG)
    private val imLong = FloatArray(N_LONG)
    private val powLong = FloatArray(N_LONG / 2 + 1)
    private val reShort = FloatArray(N_SHORT)
    private val imShort = FloatArray(N_SHORT)
    private val powShort = FloatArray(N_SHORT / 2 + 1)

    private val bandUseLong = BooleanArray(BANDS)
    private val bandBinStart = IntArray(BANDS)
    private val bandBinEnd = IntArray(BANDS)
    private val bandWeightOffset = IntArray(BANDS)
    private val filterWeights = FloatArray(2048)

    private val rawBands = FloatArray(BANDS)
    private val tmpBands = FloatArray(BANDS)
    private val latestBands = FloatArray(BANDS)
    private val readScratch = FloatArray(BANDS)

    // 2nd-order Butterworth 140 Hz LPF + asymmetric time-domain bass envelope.
    private var lpB0 = 0f
    private var lpB1 = 0f
    private var lpB2 = 0f
    private var lpA1 = 0f
    private var lpA2 = 0f
    private var lpX1 = 0f
    private var lpX2 = 0f
    private var lpY1 = 0f
    private var lpY2 = 0f
    private var bassAttackAlpha = 0.01f
    private var bassReleaseAlpha = 0.001f
    private var bassEnvRaw = 0f
    private var bassRef = REF_MIN

    // Per-region running AGC (Low 0..7, Mid 8..19, High 20..31).
    private val regionRef = FloatArray(3) { REF_MIN }
    private var globalRef = REF_MIN
    private var agcAtkAlpha = 0.35f
    private var agcRelAlpha = 0.008f
    private var hopDtSec = HOP / 44100f

    private val hopOnset = SpectralOnsetDetector(BANDS)
    private var beatSeqCounter = 0

    private var rate = 44100
    private var effRate = 44100
    private var decim = 1
    private var decimSum = 0f
    private var decimCount = 0
    private var configured = false

    /** Latest computed spectrum (0..1 per band). Prefer [sampleFrame] for interpolated draw-phase reads. */
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

    /** Last measured CPU time per analysis hop in microseconds. */
    @Volatile var analysisMicrosPerHop: Int = 0
        private set

    /** Effective (possibly decimated) sample rate used by the FFT and LPF. */
    @Volatile var effectiveSampleRateHz: Int = 44100
        private set

    /** True while a visual has asked for the spectrum in the last two seconds. The audio thread skips all tap work otherwise. */
    val wanted: Boolean
        get() = hasRead && (nowClockNs() - readAt < 2_000_000_000L)

    private val histBands = Array(HIST_SIZE) { FloatArray(BANDS) }
    private val histBass = FloatArray(HIST_SIZE)
    private val histOnset = FloatArray(HIST_SIZE)
    private val histBeatSeq = IntArray(HIST_SIZE)
    private val histPlayoutNs = LongArray(HIST_SIZE)
    private val histSeq = LongArray(HIST_SIZE)
    @Volatile private var histHead = 0
    @Volatile private var latestBass = 0f
    @Volatile private var latestOnset = 0f

    /** Nominal center frequency in Hz for band `0..31` (identical across all sample rates). */
    fun bandCenterHz(band: Int): Float = bandCenters[band.coerceIn(0, BANDS - 1)]

    /** Updates the audible playback clock anchor from the active engine/sink. Lock-free. */
    fun updatePlaybackClock(audiblePosUs: Long, speed: Float = currentSpeed, nowNs: Long = nowClockNs()) {
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
        latestBass = 0f
        latestOnset = 0f
        histPlayoutNs.fill(0L)
        hopOnset.reset()
    }

    /** Marks a non-PCM stream (such as DoP/DSD) active so visuals stay in a clean idle state. */
    fun markNonPcmStream() {
        nonPcmActive = true
        hasData = false
        latestPlayoutNs = 0L
        nextStreamPlayoutNs = 0L
        latestBands.fill(0f)
        bands.fill(0f)
        latestBass = 0f
        latestOnset = 0f
        histPlayoutNs.fill(0L)
        hopOnset.reset()
    }

    /**
     * Populates [out] with playout-time-aligned, linearly interpolated spectrum, bass envelope,
     * and fixed-hop onset state. Zero-allocation and lock-free.
     * Returns true if live, non-stale, non-paused PCM spectrum data is available.
     */
    fun sampleFrame(out: TapFrame, nowNs: Long = nowClockNs()): Boolean {
        readAt = nowNs
        hasRead = true
        if (paused || nonPcmActive || !hasData) return false
        if (nowNs > latestPlayoutNs + 250_000_000L) return false
        if (nowNs - lastPushWallNs > 1_500_000_000L) return false

        val targetNs = nowNs + calibrationOffsetMs * 1_000_000L
        val head = histHead
        var prevIdx = -1
        var prevTs = Long.MIN_VALUE
        var nextIdx = -1
        var nextTs = Long.MAX_VALUE

        for (k in 0 until HIST_SIZE) {
            val i = (head - k + HIST_SIZE) % HIST_SIZE
            val ts = histPlayoutNs[i]
            if (ts <= 0L) continue
            if (ts <= targetNs) {
                if (ts > prevTs) {
                    prevTs = ts
                    prevIdx = i
                }
            } else if (ts < nextTs) {
                nextTs = ts
                nextIdx = i
            }
        }

        val copyLen = minOf(out.bands.size, BANDS)
        out.analysisUs = analysisMicrosPerHop
        out.effRateHz = effRate

        // Interpolate smoothly between bracketing hops when both are available within 250 ms.
        if (prevIdx >= 0 && nextIdx >= 0 && nextTs > prevTs && (nextTs - prevTs) <= 250_000_000L) {
            val alpha = ((targetNs - prevTs).toFloat() / (nextTs - prevTs).toFloat()).coerceIn(0f, 1f)
            val oneMinus = 1f - alpha
            for (attempt in 0 until 3) {
                val p1 = histSeq[prevIdx]
                val n1 = histSeq[nextIdx]
                if ((p1 and 1L) != 0L || (n1 and 1L) != 0L) continue
                val bPrev = histBands[prevIdx]
                val bNext = histBands[nextIdx]
                for (b in 0 until copyLen) {
                    out.bands[b] = bPrev[b] * oneMinus + bNext[b] * alpha
                }
                out.bass = histBass[prevIdx] * oneMinus + histBass[nextIdx] * alpha
                out.onset = histOnset[prevIdx] * oneMinus + histOnset[nextIdx] * alpha
                out.beatSeq = if (alpha >= 0.5f) histBeatSeq[nextIdx] else histBeatSeq[prevIdx]
                val p2 = histSeq[prevIdx]
                val n2 = histSeq[nextIdx]
                if (p1 == p2 && n1 == n2) return true
            }
        }

        val chosen = when {
            prevIdx >= 0 -> prevIdx
            nextIdx >= 0 && nextTs - targetNs <= 500_000_000L -> nextIdx
            else -> -1
        }
        if (chosen < 0) return false

        for (attempt in 0 until 3) {
            val s1 = histSeq[chosen]
            if ((s1 and 1L) != 0L) continue
            System.arraycopy(histBands[chosen], 0, out.bands, 0, copyLen)
            out.bass = histBass[chosen]
            out.onset = histOnset[chosen]
            out.beatSeq = histBeatSeq[chosen]
            val s2 = histSeq[chosen]
            if (s1 == s2) return true
        }
        System.arraycopy(latestBands, 0, out.bands, 0, copyLen)
        out.bass = latestBass
        out.onset = latestOnset
        out.beatSeq = beatSeqCounter
        return true
    }

    /**
     * Copies the time-aligned spectrum into [out] (length >= [BANDS]).
     * Returns true if live, non-stale, non-paused PCM spectrum data is available.
     */
    fun sample(out: FloatArray, nowNs: Long = nowClockNs()): Boolean {
        readAt = nowNs
        hasRead = true
        if (paused || nonPcmActive || !hasData) return false
        if (nowNs > latestPlayoutNs + 250_000_000L) return false
        if (nowNs - lastPushWallNs > 1_500_000_000L) return false

        val targetNs = nowNs + calibrationOffsetMs * 1_000_000L
        val head = histHead
        var prevIdx = -1
        var prevTs = Long.MIN_VALUE
        var nextIdx = -1
        var nextTs = Long.MAX_VALUE

        for (k in 0 until HIST_SIZE) {
            val i = (head - k + HIST_SIZE) % HIST_SIZE
            val ts = histPlayoutNs[i]
            if (ts <= 0L) continue
            if (ts <= targetNs) {
                if (ts > prevTs) {
                    prevTs = ts
                    prevIdx = i
                }
            } else if (ts < nextTs) {
                nextTs = ts
                nextIdx = i
            }
        }

        val copyLen = minOf(out.size, BANDS)
        if (prevIdx >= 0 && nextIdx >= 0 && nextTs > prevTs && (nextTs - prevTs) <= 250_000_000L) {
            val alpha = ((targetNs - prevTs).toFloat() / (nextTs - prevTs).toFloat()).coerceIn(0f, 1f)
            val oneMinus = 1f - alpha
            for (attempt in 0 until 3) {
                val p1 = histSeq[prevIdx]
                val n1 = histSeq[nextIdx]
                if ((p1 and 1L) != 0L || (n1 and 1L) != 0L) continue
                val bPrev = histBands[prevIdx]
                val bNext = histBands[nextIdx]
                for (b in 0 until copyLen) {
                    out[b] = bPrev[b] * oneMinus + bNext[b] * alpha
                }
                val p2 = histSeq[prevIdx]
                val n2 = histSeq[nextIdx]
                if (p1 == p2 && n1 == n2) return true
            }
        }

        val chosen = when {
            prevIdx >= 0 -> prevIdx
            nextIdx >= 0 && nextTs - targetNs <= 500_000_000L -> nextIdx
            else -> -1
        }
        if (chosen < 0) return false

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
        val now = nowClockNs()
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
        val now = nowClockNs()
        readAt = now
        hasRead = true
        if (paused || nonPcmActive || !hasData) return false
        if (now > latestPlayoutNs + 250_000_000L) return false
        return now - lastPushWallNs <= 1_500_000_000L
    }

    fun reset() {
        ring.fill(0f)
        ringPos = 0
        samplesSinceHop = 0
        totalEffSamples = 0
        decimSum = 0f
        decimCount = 0
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        bassEnvRaw = 0f
        bassRef = REF_MIN
        regionRef.fill(REF_MIN)
        globalRef = REF_MIN
        hopOnset.reset()
        beatSeqCounter = 0
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
        histBass.fill(0f)
        histOnset.fill(0f)
        histBeatSeq.fill(0)
        latestBands.fill(0f)
        bands.fill(0f)
        readScratch.fill(0f)
        latestBass = 0f
        latestOnset = 0f
        pushSampleCount = 0L
        fftFrameCount = 0L
        measuredLatencyMs = 0L
        analysisMicrosPerHop = 0
    }

    /** One mono sample in -1..1. Called on the audio thread. */
    fun push(sampleRateHz: Int, s: Float) {
        pushWithPts(sampleRateHz, s, -1L, activeSourceTag)
    }

    /** One mono sample in -1..1 with an optional stream PTS in microseconds. Lock-free, zero-allocation. */
    fun pushWithPts(sampleRateHz: Int, s: Float, ptsUs: Long, sourceTag: String = "") {
        if (sampleRateHz <= 0) return
        if (!configured || sampleRateHz != rate) configure(sampleRateHz)
        if (sourceTag.isNotEmpty() && activeSourceTag != sourceTag) activeSourceTag = sourceTag
        paused = false
        nonPcmActive = false
        pushSampleCount++

        val sample = if (decim == 1) {
            s
        } else {
            decimSum += s
            decimCount++
            if (decimCount < decim) return
            val avg = decimSum / decimCount
            decimSum = 0f
            decimCount = 0
            avg
        }

        // Write to circular buffer and update 140 Hz time-domain bass envelope.
        ring[ringPos] = sample
        ringPos = (ringPos + 1) and (N_LONG - 1)
        totalEffSamples++
        samplesSinceHop++

        val lp = lpB0 * sample + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
        lpX2 = lpX1; lpX1 = sample
        lpY2 = lpY1; lpY1 = lp
        val absLp = abs(lp)
        val bAlpha = if (absLp > bassEnvRaw) bassAttackAlpha else bassReleaseAlpha
        bassEnvRaw += (absLp - bassEnvRaw) * bAlpha

        if (totalEffSamples < N_SHORT || samplesSinceHop < HOP) return
        samplesSinceHop = 0
        analyse(ptsUs)
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
        effectiveSampleRateHz = effRate
        decimSum = 0f
        decimCount = 0
        ring.fill(0f)
        ringPos = 0
        samplesSinceHop = 0
        totalEffSamples = 0

        // 2nd-order Butterworth LPF at 140 Hz.
        val k = tan(PI * BASS_LPF_HZ / effRate.toDouble())
        val norm = 1.0 / (1.0 + sqrt(2.0) * k + k * k)
        lpB0 = (k * k * norm).toFloat()
        lpB1 = 2f * lpB0
        lpB2 = lpB0
        lpA1 = (2.0 * (k * k - 1.0) * norm).toFloat()
        lpA2 = ((1.0 - sqrt(2.0) * k + k * k) * norm).toFloat()
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        bassAttackAlpha = (1.0 - exp(-1.0 / (0.008 * effRate))).toFloat()
        bassReleaseAlpha = (1.0 - exp(-1.0 / (0.075 * effRate))).toFloat()
        bassEnvRaw = 0f

        hopDtSec = HOP.toFloat() / effRate.toFloat()
        agcAtkAlpha = (1.0 - exp(-hopDtSec / 0.025)).toFloat()
        agcRelAlpha = (1.0 - exp(-hopDtSec / 1.6)).toFloat()

        // Build exact-Hz triangular filterbank across dual-resolution FFTs.
        val ratio = (MAX_HZ / MIN_HZ).pow(1.0 / (BANDS - 1))
        val nyquistLimit = effRate * 0.495
        var wCursor = 0
        for (b in 0 until BANDS) {
            val fC = bandCenters[b].toDouble()
            val fL = if (b == 0) MIN_HZ / ratio else bandCenters[b - 1].toDouble()
            val fR = minOf(if (b == BANDS - 1) MAX_HZ * ratio else bandCenters[b + 1].toDouble(), nyquistLimit)
            val useLong = fC <= LOW_SPLIT_HZ
            bandUseLong[b] = useLong
            val fftN = if (useLong) N_LONG else N_SHORT
            val maxBin = fftN / 2
            val df = effRate.toDouble() / fftN

            var binStart = (fL / df).toInt().coerceIn(1, maxBin - 1)
            var binEnd = ((fR / df).toInt() + 1).coerceIn(binStart + 1, maxBin + 1)
            val centerBin = (fC / df).toInt().coerceIn(1, maxBin)
            if (binStart > centerBin) binStart = centerBin
            if (binEnd <= centerBin) binEnd = (centerBin + 1).coerceAtMost(maxBin + 1)

            bandBinStart[b] = binStart
            bandBinEnd[b] = binEnd
            bandWeightOffset[b] = wCursor

            var wSum = 0f
            val leftSpan = (fC - fL).coerceAtLeast(df * 0.5)
            val rightSpan = (fR - fC).coerceAtLeast(df * 0.5)
            val startOff = wCursor
            for (bin in binStart until binEnd) {
                val freq = bin * df
                val w = when {
                    freq < fL || freq > fR -> 0f
                    freq <= fC -> ((freq - fL) / leftSpan).toFloat()
                    else -> ((fR - freq) / rightSpan).toFloat()
                }.coerceIn(0f, 1f)
                if (wCursor < filterWeights.size) {
                    filterWeights[wCursor++] = w
                    wSum += w
                }
            }
            // Guarantee at least one active bin if narrow band falls between discrete bins.
            if (wSum <= 1e-4f && startOff < filterWeights.size) {
                val localIdx = (centerBin - binStart).coerceIn(0, (binEnd - binStart - 1).coerceAtLeast(0))
                if (startOff + localIdx < filterWeights.size) {
                    filterWeights[startOff + localIdx] = 1f
                }
            }
        }
        configured = true
    }

    private fun analyse(ptsUs: Long) {
        val startNs = nowClockNs()

        // 1. Long FFT (4096, Blackman-Harris) for <= 550 Hz resolution (~10.8 Hz/bin).
        var idxL = ringPos
        for (i in 0 until N_LONG) {
            reLong[i] = ring[idxL] * windowLong[i]
            imLong[i] = 0f
            idxL = (idxL + 1) and (N_LONG - 1)
        }
        fft(N_LONG, reLong, imLong, bitRevLong, twCosLong, twSinLong)
        val longBins = N_LONG / 2
        for (k in 0..longBins) {
            powLong[k] = (reLong[k] * reLong[k] + imLong[k] * imLong[k]) * winLongNorm
        }

        // 2. Short FFT (1024, Hann) over the most recent 1024 samples for crisp mid/high transients.
        var idxS = (ringPos - N_SHORT + N_LONG) and (N_LONG - 1)
        for (i in 0 until N_SHORT) {
            reShort[i] = ring[idxS] * windowShort[i]
            imShort[i] = 0f
            idxS = (idxS + 1) and (N_LONG - 1)
        }
        fft(N_SHORT, reShort, imShort, bitRevShort, twCosShort, twSinShort)
        val shortBins = N_SHORT / 2
        for (k in 0..shortBins) {
            powShort[k] = (reShort[k] * reShort[k] + imShort[k] * imShort[k]) * winShortNorm
        }

        // 3. Aggregate RMS power per triangular filterband + perceptual tilt.
        var lowPeak = 0f
        var midPeak = 0f
        var highPeak = 0f
        var globalPeak = 0f
        for (b in 0 until BANDS) {
            val powArr = if (bandUseLong[b]) powLong else powShort
            val start = bandBinStart[b]
            val end = bandBinEnd[b]
            var wOff = bandWeightOffset[b]
            var energySum = 0f
            for (k in start until end) {
                val w = if (wOff < filterWeights.size) filterWeights[wOff++] else 0f
                energySum += w * powArr[k]
            }
            val valBand = sqrt(energySum.coerceAtLeast(0f)) * bandTilt[b]
            rawBands[b] = valBand
            if (valBand > globalPeak) globalPeak = valBand
            when {
                b < 8 -> if (valBand > lowPeak) lowPeak = valBand
                b < 20 -> if (valBand > midPeak) midPeak = valBand
                else -> if (valBand > highPeak) highPeak = valBand
            }
        }

        // 4. Per-region running AGC (fast attack 25 ms, medium release 1.6 s) + 48 dB log curve.
        globalRef = updateAgc(globalRef, globalPeak)
        regionRef[0] = updateAgc(regionRef[0], lowPeak)
        regionRef[1] = updateAgc(regionRef[1], midPeak)
        regionRef[2] = updateAgc(regionRef[2], highPeak)

        for (b in 0 until BANDS) {
            val raw = rawBands[b]
            if (raw < SILENCE_FLOOR) {
                tmpBands[b] = 0f
            } else {
                val reg = when {
                    b < 8 -> regionRef[0]
                    b < 20 -> regionRef[1]
                    else -> regionRef[2]
                }
                val c = maxOf(0.65f * globalRef + 0.35f * reg, REF_MIN)
                val db = 20f * log10(raw / c)
                val x = ((db + DB_WINDOW) / DB_WINDOW).coerceIn(0f, 1f)
                tmpBands[b] = x.pow(1.25f)
            }
        }

        // 5. Normalize time-domain 140 Hz bass envelope with its own AGC.
        bassRef = updateAgc(bassRef, bassEnvRaw)
        val bassNorm = if (bassEnvRaw < 0.0004f) {
            0f
        } else {
            val c = maxOf(bassRef, 0.002f)
            val db = 20f * log10(bassEnvRaw / c)
            val x = ((db + DB_WINDOW) / DB_WINDOW).coerceIn(0f, 1f)
            x.pow(1.15f)
        }

        // 6. Fixed-hop spectral flux + bass derivative onset detection.
        if (hopOnset.updateHop(tmpBands, bassNorm, hopDtSec)) {
            beatSeqCounter++
        }
        val onsetNorm = hopOnset.onset

        val now = nowClockNs()
        lastPushWallNs = now
        val playoutNs = computePlayoutNs(now, ptsUs)
        latestPlayoutNs = playoutNs
        measuredLatencyMs = ((playoutNs - now) / 1_000_000L).coerceIn(0L, 2_000L)

        val slot = (histHead + 1) % HIST_SIZE
        val seq = histSeq[slot]
        histSeq[slot] = seq + 1L // odd = writing
        System.arraycopy(tmpBands, 0, histBands[slot], 0, BANDS)
        histBass[slot] = bassNorm
        histOnset[slot] = onsetNorm
        histBeatSeq[slot] = beatSeqCounter
        histPlayoutNs[slot] = playoutNs
        histSeq[slot] = seq + 2L // even = stable
        histHead = slot

        System.arraycopy(tmpBands, 0, latestBands, 0, BANDS)
        System.arraycopy(tmpBands, 0, bands, 0, BANDS)
        latestBass = bassNorm
        latestOnset = onsetNorm
        hasData = true
        fftFrameCount++

        val elapsedUs = ((now - startNs) / 1_000L).toInt()
        if (elapsedUs >= 0) analysisMicrosPerHop = elapsedUs
    }

    private fun updateAgc(current: Float, peak: Float): Float {
        val alpha = if (peak > current) agcAtkAlpha else agcRelAlpha
        return maxOf(current + (peak - current) * alpha, REF_MIN)
    }

    private fun computePlayoutNs(nowNs: Long, ptsUs: Long): Long {
        val anchorWall = anchorWallNs
        val anchorPos = anchorPosUs
        if (ptsUs >= 0L && anchorWall > 0L && anchorPos >= 0L && nowNs - anchorWall < 2_000_000_000L) {
            val centerOffsetUs = ((N_SHORT / 2).toLong() * 1_000_000L) / effRate
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

    private fun fft(
        n: Int,
        re: FloatArray,
        im: FloatArray,
        bitRev: IntArray,
        twCos: FloatArray,
        twSin: FloatArray,
    ) {
        for (i in 1 until n) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var twIdx = 0
                for (k in 0 until half) {
                    val cr = twCos[twIdx]
                    val ci = twSin[twIdx]
                    val idx1 = i + k
                    val idx2 = idx1 + half
                    val ur = re[idx1]; val ui = im[idx1]
                    val vr = re[idx2] * cr - im[idx2] * ci
                    val vi = re[idx2] * ci + im[idx2] * cr
                    re[idx1] = ur + vr; im[idx1] = ui + vi
                    re[idx2] = ur - vr; im[idx2] = ui - vi
                    twIdx += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
