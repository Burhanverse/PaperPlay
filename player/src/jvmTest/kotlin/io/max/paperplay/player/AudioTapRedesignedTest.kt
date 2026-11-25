package io.max.paperplay.player

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioTapRedesignedTest {
    private var nowNs = 5_000_000_000_000L

    @BeforeTest
    fun setUp() {
        nowNs = 5_000_000_000_000L
        AudioTap.clockNs = { nowNs }
        AudioTap.calibrationOffsetMs = 0L
        AudioTap.displayLatencyMs = 0L
        AudioTap.setReportedLatencyMs(80L)
        AudioTap.setSpeed(1f)
        AudioTap.reset()
    }

    @AfterTest
    fun tearDown() {
        AudioTap.reset()
        AudioTap.calibrationOffsetMs = 0L
        AudioTap.displayLatencyMs = 0L
        AudioTap.setReportedLatencyMs(80L)
        AudioTap.setSpeed(1f)
        AudioTap.clockNs = { System.nanoTime() }
    }

    private fun feedSine(hz: Double, seconds: Double, rate: Int = 44100, amp: Float = 0.5f) {
        val total = (rate * seconds).toInt()
        for (i in 0 until total) {
            AudioTap.push(rate, (amp * sin(2.0 * PI * hz * i / rate)).toFloat())
        }
    }

    @Test
    fun pureSineAtEveryBandCenterLightsItsOwnBandWithLowLeakage() {
        for (b in 0 until AudioTap.BANDS) {
            AudioTap.reset()
            val hz = AudioTap.bandCenterHz(b).toDouble()
            feedSine(hz, 0.35, rate = 48_000, amp = 0.4f)
            val bands = AudioTap.bands
            val peak = bands.indices.maxByOrNull { bands[it] } ?: -1
            assertTrue(
                abs(peak - b) <= 1,
                "Band $b (${hz}Hz) peaked at $peak (${AudioTap.bandCenterHz(peak)}Hz): ${bands.toList()}",
            )
            assertTrue(bands[peak] > 0.7f, "Peak band $peak for ${hz}Hz too low: ${bands[peak]}")
            for (k in 0 until AudioTap.BANDS) {
                val dist = abs(k - b)
                if (dist >= 3) {
                    val limit = if (dist >= 4) 0.25f else 0.36f
                    assertTrue(
                        bands[k] < limit,
                        "Excessive leakage at band $k=${bands[k]} (limit=$limit) for center band $b (${hz}Hz)",
                    )
                }
            }
        }
    }

    @Test
    fun bandEdgesInHzMatchAcrossAllSampleRates() {
        val testFreqs = listOf(60.0, 100.0, 440.0, 1000.0, 4000.0, 10_000.0)
        val rates = listOf(44_100, 48_000, 88_200, 96_000, 192_000)

        for (hz in testFreqs) {
            val peaks = rates.map { rate ->
                AudioTap.reset()
                feedSine(hz, 0.3, rate = rate, amp = 0.4f)
                AudioTap.bands.indices.maxByOrNull { AudioTap.bands[it] } ?: -1
            }
            val span = peaks.max() - peaks.min()
            assertTrue(span <= 1, "Freq ${hz}Hz landed on inconsistent bands across rates: $peaks")
        }
    }

    @Test
    fun loudAndQuietMastersBothUseFullRangeAndPreserveRelativeLevels() {
        AudioTap.reset()
        feedSine(1000.0, 0.4, rate = 44100, amp = 0.50f) // -6 dBFS
        val loudPeak = AudioTap.bands.max()

        AudioTap.reset()
        feedSine(1000.0, 0.4, rate = 44100, amp = 0.05f) // -26 dBFS
        val quietPeak = AudioTap.bands.max()

        assertTrue(loudPeak > 0.85f, "Loud master peak=$loudPeak")
        assertTrue(quietPeak > 0.75f, "Quiet master peak=$quietPeak")
    }

    @Test
    fun loudHitFollowedByQuietPassageRecoversWithinReleaseTime() {
        val rate = 44100
        // 0.5 s loud 65 Hz hit
        feedSine(65.0, 0.5, rate = rate, amp = 0.95f)
        // Followed by 2.2 s quiet 1 kHz tone
        feedSine(1000.0, 2.2, rate = rate, amp = 0.08f)
        val midPeak = (12..19).maxOf { AudioTap.bands[it] }
        assertTrue(midPeak > 0.45f, "Quiet passage stayed flattened after loud hit: midPeak=$midPeak")
    }

    @Test
    fun silenceProducesStrictlyZeroBandsBassAndOnsets() {
        val frame = AudioTap.TapFrame()
        AudioTap.sampleFrame(frame, nowNs)
        repeat(44100) { AudioTap.push(44100, 0f) }
        assertTrue(AudioTap.bands.all { it == 0f }, "Digital silence must be strictly 0f")
        assertTrue(AudioTap.sampleFrame(frame, nowNs))
        assertEquals(0f, frame.bass)
        assertEquals(0f, frame.onset)

        AudioTap.reset()
        AudioTap.sampleFrame(frame, nowNs)
        // -80 dBFS noise
        for (i in 0 until 22050) {
            AudioTap.push(44100, 0.0001f * sin(2.0 * PI * 330.0 * i / 44100.0).toFloat())
        }
        assertTrue(AudioTap.bands.all { it == 0f }, "-80 dBFS must stay gated at 0f")
    }

    @Test
    fun logSweepMovesPeakMonotonicallyFromLowToHigh() {
        val rate = 44100
        val dur = 3.0
        val n = (rate * dur).toInt()
        val f0 = 45.0
        val f1 = 13_000.0
        val k = kotlin.math.ln(f1 / f0) / dur
        val peaks = mutableListOf<Int>()
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val phase = 2.0 * PI * f0 * (kotlin.math.exp(k * t) - 1.0) / k
            AudioTap.push(rate, (0.5 * sin(phase)).toFloat())
            if (i > 4096 && i % 6000 == 0) {
                peaks += AudioTap.bands.indices.maxByOrNull { AudioTap.bands[it] } ?: 0
            }
        }
        assertTrue(peaks.first() <= 4, "Sweep must start low: $peaks")
        assertTrue(peaks.last() >= 26, "Sweep must reach high: $peaks")
        for (idx in 1 until peaks.size) {
            assertTrue(peaks[idx] >= peaks[idx - 1] - 1, "Sweep must progress monotonically: $peaks")
        }
    }

    @Test
    fun displayRateInterpolationIsContinuousAcross60_120_144Hz() {
        val frame = AudioTap.TapFrame()
        AudioTap.sampleFrame(frame, nowNs)
        AudioTap.updatePlaybackClock(0L, 1f, nowNs)
        val rate = 44100
        // Silence first, then sudden 1 kHz tone at 200 ms
        for (i in 0 until (rate * 0.6).toInt()) {
            val ptsUs = (i * 1_000_000L) / rate
            val amp = if (i < rate / 5) 0f else 0.7f
            AudioTap.pushWithPts(rate, amp * sin(2.0 * PI * 1000.0 * i / rate).toFloat(), ptsUs, "interp-test")
        }
        val samples144 = mutableListOf<Float>()
        val stepNs = 1_000_000_000L / 144L
        var t = nowNs + 150_000_000L
        repeat(20) {
            if (AudioTap.sampleFrame(frame, t)) {
                samples144 += frame.bands.max()
            }
            t += stepNs
        }
        assertTrue(samples144.size >= 15)
        assertTrue(samples144.first() < 0.2f && samples144.last() > 0.7f, "Expected smooth rise: $samples144")
    }

    @Test
    fun zeroAllocationsAndLowCpuCostOnAudioThread() {
        AudioTap.clockNs = AudioTap.clockNs // keep reference, use real System.nanoTime via reset below
        AudioTap.reset()
        // Use production clock path (no lambda boxing)
        val prodTestNs = System.nanoTime()
        AudioTap.clockNs = AudioTap::class.java.getDeclaredField("defaultClock").apply { isAccessible = true }.get(AudioTap) as () -> Long

        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val tid = Thread.currentThread().id

        val frame = AudioTap.TapFrame()
        AudioTap.sampleFrame(frame, prodTestNs)
        AudioTap.updatePlaybackClock(0L, 1f, prodTestNs)

        // Warm up JIT
        for (i in 0 until 512 * 3000) {
            AudioTap.pushWithPts(44100, (0.4 * sin(2.0 * PI * 220.0 * i / 44100.0)).toFloat(), (i * 1_000_000L) / 44100, "bench")
            if (i % 512 == 0) AudioTap.sampleFrame(frame)
        }

        val totalSamples = 512 * 400 // 400 hops (~4.6 s of audio)
        val t0 = System.nanoTime()
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until totalSamples) {
            AudioTap.pushWithPts(44100, (0.4 * sin(2.0 * PI * 440.0 * i / 44100.0)).toFloat(), (i * 1_000_000L) / 44100, "bench")
            if ((i and 511) == 0) AudioTap.sampleFrame(frame)
        }
        val allocated = bean.getThreadAllocatedBytes(tid) - before
        val elapsedNs = System.nanoTime() - t0
        val usPerHop = elapsedNs / 1000.0 / 400.0
        println("AudioTap bench: allocated=${allocated}B across 400 hops, avg=${"%.1f".format(usPerHop)} us/hop (budget 11610 us/hop at 44.1kHz)")
        assertTrue(allocated <= 512L, "Audio thread + sampleFrame must stay allocation-free (<=512B JIT noise), got $allocated")
        assertTrue(usPerHop < 1500.0, "Analysis per hop too slow: $usPerHop us")
    }

    @Test
    fun matchesPythonReferenceAnalyzerWithHighCorrelation() {
        val repoRoot = listOf(File("."), File("..")).firstOrNull { File(it, "scripts/analyze_audio_ref.py").isFile }
            ?: error("Could not locate scripts/analyze_audio_ref.py")
        val script = File(repoRoot, "scripts/analyze_audio_ref.py")
        val tmpDir = File.createTempFile("audiotap_ref", "").apply { delete(); mkdirs() }
        val jsonOut = File(tmpDir, "ref.json")
        try {
            val proc = ProcessBuilder(
                "python3", script.absolutePath,
                "--generate-fixtures", tmpDir.absolutePath,
                "--out", jsonOut.absolutePath,
            ).redirectErrorStream(true).start()
            val outText = proc.inputStream.bufferedReader().readText()
            assertEquals(0, proc.waitFor(), "Reference analyzer failed:\n$outText")

            val json = jsonOut.readText()
            val frame = AudioTap.TapFrame()

            // Test click tracks across 44.1 / 48 / 96 / 192 kHz for latency & zero false pulses
            for (sr in listOf(44100, 48000, 96000, 192000)) {
                val wav = File(tmpDir, "click_120bpm_$sr.wav")
                val (wavSr, samples) = readWavMono(wav)
                assertEquals(sr, wavSr)

                val baseNs = 5_000_000_000_000L
                nowNs = baseNs
                AudioTap.reset()
                AudioTap.sampleFrame(frame, nowNs)
                AudioTap.updatePlaybackClock(0L, 1f, nowNs)
                var prevBeatSeq = 0
                val detectedBeatsSec = mutableListOf<Double>()
                for (i in samples.indices) {
                    nowNs = baseNs + (i.toLong() * 1_000_000_000L) / sr
                    val ptsUs = (i.toLong() * 1_000_000L) / sr
                    val beforeFft = AudioTap.fftFrameCount
                    AudioTap.pushWithPts(sr, samples[i], ptsUs, "click-$sr")
                    if (AudioTap.fftFrameCount > beforeFft) {
                        if (AudioTap.sampleFrame(frame, nowNs) && frame.beatSeq != prevBeatSeq) {
                            prevBeatSeq = frame.beatSeq
                            detectedBeatsSec += i.toDouble() / sr
                        }
                    }
                }
                // Expected clicks at 0.25, 0.75, 1.25, 1.75, 2.25, 2.75 s
                val expected = listOf(0.25, 0.75, 1.25, 1.75, 2.25, 2.75)
                var matched = 0
                var maxErrMs = 0.0
                for (expSec in expected) {
                    val nearest = detectedBeatsSec.minByOrNull { abs(it - expSec) }
                    if (nearest != null && abs(nearest - expSec) <= 0.025) {
                        matched++
                        maxErrMs = maxOf(maxErrMs, abs(nearest - expSec) * 1000.0)
                    }
                }
                val falsePulses = (detectedBeatsSec.size - matched).coerceAtLeast(0)
                println("Click $sr Hz: matched=$matched/${expected.size} maxErr=${"%.1f".format(maxErrMs)}ms false=$falsePulses")
                assertTrue(matched >= 5, "Missed clicks at $sr Hz: detected=$detectedBeatsSec")
                assertEquals(0, falsePulses, "False beat pulses at $sr Hz: $detectedBeatsSec")
            }

            // Correlate Kotlin vs Python on club loop, pop loop, pink noise, and dynamics
            for (name in listOf("loop_club_124bpm", "loop_pop_105bpm", "loop_dynamics", "pink_noise")) {
                val wav = File(tmpDir, "$name.wav")
                val (sr, samples) = readWavMono(wav)
                val ref = extractArrayPair(json, name)

                val baseNs = 5_000_000_000_000L
                nowNs = baseNs
                AudioTap.reset()
                AudioTap.setReportedLatencyMs(0L)
                AudioTap.sampleFrame(frame, nowNs)
                val ktBands = mutableListOf<FloatArray>()
                val ktBass = mutableListOf<Float>()
                for (i in samples.indices) {
                    nowNs = baseNs + (i.toLong() * 1_000_000_000L) / sr
                    val beforeFft = AudioTap.fftFrameCount
                    AudioTap.push(sr, samples[i])
                    if (AudioTap.fftFrameCount > beforeFft) {
                        ktBands += AudioTap.bands.copyOf()
                        AudioTap.sampleFrame(frame, nowNs)
                        ktBass += frame.bass
                    }
                }
                val nHops = minOf(ktBands.size, ref.bands.size)
                assertTrue(nHops > 50, "Too few hops for $name: $nHops")

                val flatKt = DoubleArray(nHops * AudioTap.BANDS)
                val flatRef = DoubleArray(nHops * AudioTap.BANDS)
                for (h in 0 until nHops) {
                    for (b in 0 until AudioTap.BANDS) {
                        flatKt[h * AudioTap.BANDS + b] = ktBands[h][b].toDouble()
                        flatRef[h * AudioTap.BANDS + b] = ref.bands[h][b]
                    }
                }
                val rBands = pearson(flatKt, flatRef)
                val rBass = pearson(
                    DoubleArray(nHops) { ktBass[it].toDouble() },
                    DoubleArray(nHops) { ref.bass[it] },
                )
                println("Correlation $name ($nHops hops): bands r=${"%.4f".format(rBands)}, bass r=${"%.4f".format(rBass)}")
                assertTrue(rBands >= 0.92, "Band envelope correlation too low for $name: r=$rBands")
                assertTrue(rBass >= 0.92, "Bass envelope correlation too low for $name: r=$rBass")
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun readWavMono(file: File): Pair<Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sr = buf.getInt(24)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val sz = buf.getInt(pos + 4)
            if (id == "data") {
                val count = sz / 2
                val out = FloatArray(count)
                for (i in 0 until count) {
                    out[i] = buf.getShort(pos + 8 + i * 2) / 32768f
                }
                return sr to out
            }
            pos += 8 + sz
        }
        error("No data chunk in ${file.name}")
    }

    private class RefData(val bands: List<DoubleArray>, val bass: DoubleArray)

    private fun extractArrayPair(json: String, key: String): RefData {
        val keyIdx = json.indexOf("\"$key\":")
        require(keyIdx >= 0) { "Key $key not found" }
        val bandsKey = json.indexOf("\"bands\":", keyIdx)
        val bassKey = json.indexOf("\"bass\":", bandsKey)
        val onsetKey = json.indexOf("\"onset\":", bassKey)

        val bandsStr = json.substring(json.indexOf("[[", bandsKey) + 1, json.lastIndexOf("]]", bassKey) + 1)
        val bands = mutableListOf<DoubleArray>()
        var i = 0
        while (i < bandsStr.length) {
            val s = bandsStr.indexOf('[', i)
            if (s < 0) break
            val e = bandsStr.indexOf(']', s)
            val parts = bandsStr.substring(s + 1, e).split(',')
            bands += DoubleArray(parts.size) { parts[it].trim().toDouble() }
            i = e + 1
        }
        val bassStr = json.substring(json.indexOf('[', bassKey) + 1, json.indexOf(']', bassKey))
        val bassParts = bassStr.split(',')
        val bass = DoubleArray(bassParts.size) { bassParts[it].trim().toDouble() }
        assertTrue(onsetKey > bassKey)
        return RefData(bands, bass)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) {
            val xa = a[i] - ma
            val xb = b[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        val den = sqrt(da * db)
        return if (den < 1e-12) 1.0 else num / den
    }

    @Test
    fun deepBuffer4SecondsDoesNotOverflowHistoryOrDropOut() {
        val frame = AudioTap.TapFrame()
        AudioTap.sampleFrame(frame, nowNs)
        val rate = 44100
        // Simulate 4.0 s deep-buffer AudioTrack: initial 4.0s fill, then 2.0s refill bursts
        // while playback advances ~1.9s between bursts (matching Android FLAG_DEEP_BUFFER).
        var written = 0
        var played = 0
        val initFill = rate * 4
        AudioTap.updatePlaybackClock(0L, 1f, nowNs)
        for (i in 0 until initFill) {
            val ptsUs = (i.toLong() * 1_000_000L) / rate
            AudioTap.pushWithPts(rate, 0.5f * sin(2.0 * PI * 220.0 * i / rate).toFloat(), ptsUs, "deep-buf")
        }
        written = initFill
        repeat(3) {
            for (step in 1..114) {
                nowNs += 16_666_667L
                played += (rate / 60)
                val anchorUs = (played.toLong() * 1_000_000L) / rate
                AudioTap.updatePlaybackClock(anchorUs, 1f, nowNs)
                assertTrue(AudioTap.sampleFrame(frame, nowNs), "Deep buffer must not drop out during 2s refill pause")
            }
            val burst = rate * 2
            val end = written + burst
            for (i in written until end) {
                val ptsUs = (i.toLong() * 1_000_000L) / rate
                AudioTap.pushWithPts(rate, 0.5f * sin(2.0 * PI * 220.0 * i / rate).toFloat(), ptsUs, "deep-buf")
            }
            written = end
        }
        assertTrue(AudioTap.measuredLatencyMs in 3500L..4500L, "Expected deep-buffer latency ~4000ms, got ${AudioTap.measuredLatencyMs}")
    }

    @Test
    fun pllClockSmoothsTimestampJitterAndSnapsOnSeek() {
        val frame = AudioTap.TapFrame()
        AudioTap.sampleFrame(frame, nowNs)
        val rate = 44100
        val hop = 512
        val expectedHopMs = hop * 1000.0 / rate // ~11.61 ms
        val startNs = nowNs

        for (h in 0 until 40) {
            val sampleBase = h * hop
            val ptsUs = (sampleBase.toLong() * 1_000_000L) / rate
            // Inject +-15 ms anchor jitter every hop
            val jitterUs = if (h % 2 == 0) 15_000L else -15_000L
            AudioTap.updatePlaybackClock((ptsUs - 80_000L + jitterUs).coerceAtLeast(0L), 1f, nowNs)
            for (i in 0 until hop) {
                val sPts = ((sampleBase + i).toLong() * 1_000_000L) / rate
                AudioTap.pushWithPts(rate, 0.4f * sin(2.0 * PI * 440.0 * (sampleBase + i) / rate).toFloat(), sPts, "pll-test")
            }
            nowNs += (hop * 1_000_000_000L) / rate
        }

        // Verify consecutive hop deltas on framePlayoutNs in interior range are tightly smoothed despite +-15ms anchor jitter
        val deltasMs = mutableListOf<Double>()
        var prevNs = 0L
        for (h in 3..25) {
            val t = startNs + h * ((hop * 1_000_000_000L) / rate) + 65_000_000L
            if (AudioTap.sampleFrame(frame, t)) {
                if (prevNs > 0L && frame.framePlayoutNs > prevNs) {
                    deltasMs += (frame.framePlayoutNs - prevNs) / 1_000_000.0
                }
                prevNs = frame.framePlayoutNs
            }
        }
        assertTrue(deltasMs.size >= 15 && deltasMs.all { abs(it - expectedHopMs) < 3.5 }, "PLL should smooth +-15ms jitter, got deltas=$deltasMs")

        // Seek +500 ms -> PLL must snap immediately
        AudioTap.updatePlaybackClock(5_000_000L, 1f, nowNs)
        for (i in 0 until hop) {
            AudioTap.pushWithPts(rate, 0.4f * sin(2.0 * PI * 440.0 * i / rate).toFloat(), 5_500_000L + (i * 1_000_000L) / rate, "pll-seek")
        }
        assertTrue(AudioTap.measuredLatencyMs in 420L..560L, "PLL must snap on seek, got ${AudioTap.measuredLatencyMs}ms")
    }

    @Test
    fun displayLatencyMsShiftsSampledPlayoutForward() {
        val frame = AudioTap.TapFrame()
        AudioTap.sampleFrame(frame, nowNs)
        AudioTap.updatePlaybackClock(0L, 1f, nowNs)
        val rate = 44100
        // Silence for 200ms, then loud 1 kHz tone
        for (i in 0 until (rate * 0.5).toInt()) {
            val amp = if (i < rate / 5) 0f else 0.7f
            AudioTap.pushWithPts(rate, amp * sin(2.0 * PI * 1000.0 * i / rate).toFloat(), (i * 1_000_000L) / rate, "disp-lat")
        }
        val probeNs = nowNs + 170_000_000L
        AudioTap.displayLatencyMs = 0L
        assertTrue(AudioTap.sampleFrame(frame, probeNs))
        val atZero = frame.bands.max()

        AudioTap.displayLatencyMs = 25L
        assertTrue(AudioTap.sampleFrame(frame, probeNs))
        val at25 = frame.bands.max()
        assertTrue(at25 > atZero + 0.15f, "displayLatencyMs=25 should look 25ms ahead into rising tone: atZero=$atZero at25=$at25")
        assertEquals(0L, AudioTap.calibrationOffsetMs)
    }

    @Test
    fun interpolationProducesDistinctValuesOnEvery144HzFrame() {
        val frame = AudioTap.TapFrame()
        AudioTap.sampleFrame(frame, nowNs)
        AudioTap.updatePlaybackClock(0L, 1f, nowNs)
        val rate = 44100
        for (i in 0 until (rate * 0.6).toInt()) {
            val amp = if (i < rate / 5) 0f else 0.75f
            AudioTap.pushWithPts(rate, amp * sin(2.0 * PI * 1000.0 * i / rate).toFloat(), (i * 1_000_000L) / rate, "144hz")
        }
        val stepNs = 1_000_000_000L / 144L
        var t = nowNs + 185_000_000L
        var prevAlpha = -1f
        var interpCount = 0
        var sameDraws = 0
        repeat(12) {
            if (AudioTap.sampleFrame(frame, t)) {
                if (frame.interpolated) interpCount++
                if (prevAlpha >= 0f && abs(frame.interpAlpha - prevAlpha) < 0.001f) sameDraws++
                prevAlpha = frame.interpAlpha
            }
            t += stepNs
        }
        assertTrue(interpCount >= 10, "Expected continuous interpolation at 144Hz, got $interpCount/12")
        assertEquals(0, sameDraws, "Expected distinct interpAlpha on every 144Hz frame")
    }
}
