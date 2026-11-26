package io.max.paperplay.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioTapTest {
    private var nowNs = 1_000_000_000_000L

    @BeforeTest
    fun setUp() {
        nowNs = 1_000_000_000_000L
        AudioTap.clockNs = { nowNs }
        AudioTap.calibrationOffsetMs = 0L
        AudioTap.setReportedLatencyMs(80L)
        AudioTap.setSpeed(1f)
        AudioTap.reset()
    }

    @AfterTest
    fun tearDown() {
        AudioTap.reset()
        AudioTap.calibrationOffsetMs = 0L
        AudioTap.setReportedLatencyMs(80L)
        AudioTap.setSpeed(1f)
        AudioTap.clockNs = { System.nanoTime() }
    }

    private fun feed(hz: Double, seconds: Double, rate: Int = 44100, amp: Float = 0.5f) {
        val total = (rate * seconds).toInt()
        for (i in 0 until total) {
            AudioTap.push(rate, (amp * sin(2 * PI * hz * i / rate)).toFloat())
        }
    }

    private fun peakBand(): Int = AudioTap.bands.indices.maxByOrNull { AudioTap.bands[it] }!!

    @Test
    fun silenceStaysFlat() {
        repeat(44100) { AudioTap.push(44100, 0f) }
        assertTrue(AudioTap.bands.all { it < 0.01f }, "silence should show nothing: ${AudioTap.bands.toList()}")
    }

    @Test
    fun aToneLightsItsOwnBandAndMovesWithPitch() {
        feed(200.0, 0.5)
        val low = peakBand()
        AudioTap.reset()
        feed(3000.0, 0.5)
        val high = peakBand()
        assertTrue(high > low + 8, "3 kHz should sit well above 200 Hz: low=$low high=$high")
    }

    @Test
    fun differentSoundsGiveDifferentPictures() {
        feed(100.0, 0.5)
        val a = AudioTap.bands.copyOf()
        AudioTap.reset()
        feed(5000.0, 0.5)
        val b = AudioTap.bands.copyOf()
        assertTrue(a.indices.sumOf { abs(a[it] - b[it]).toDouble() } > 3.0, "the spectrum must depend on the audio")
    }

    @Test
    fun highSampleRatesDecimateCleanlyAndPreserveBassResolution() {
        for (rate in listOf(96_000, 192_000, 384_000)) {
            AudioTap.reset()
            feed(100.0, 0.3, rate = rate)
            val lowPeak = peakBand()
            assertTrue(lowPeak in 1..7, "100 Hz at $rate Hz should land in low band, got $lowPeak")

            AudioTap.reset()
            feed(4000.0, 0.3, rate = rate)
            val highPeak = peakBand()
            assertTrue(highPeak > lowPeak + 10, "4 kHz at $rate Hz should be well above 100 Hz: low=$lowPeak high=$highPeak")
        }
    }

    @Test
    fun burstWritePlayoutSchedulingAvoids250msDropoutsAndCompensatesDelay() {
        val out = FloatArray(AudioTap.BANDS)
        assertFalse(AudioTap.sample(out, nowNs), "no data yet -> sample must be false")
        assertTrue(AudioTap.wanted, "sample() must mark tap wanted")

        AudioTap.updatePlaybackClock(audiblePosUs = 0L, speed = 1f, nowNs = nowNs)
        // Burst-write 400 ms of audio (first 200 ms = 150 Hz, next 200 ms = 4000 Hz) in 0 ms wall time
        val rate = 44100
        for (i in 0 until rate / 5) {
            val ptsUs = (i * 1_000_000L) / rate
            AudioTap.pushWithPts(rate, 0.5f * sin(2 * PI * 150.0 * i / rate).toFloat(), ptsUs, "burst-test")
        }
        for (i in rate / 5 until (rate * 2) / 5) {
            val ptsUs = (i * 1_000_000L) / rate
            AudioTap.pushWithPts(rate, 0.5f * sin(2 * PI * 4000.0 * i / rate).toFloat(), ptsUs, "burst-test")
        }

        // Advance wall clock by 80 ms (inside first half): must stay live without dropout and show low-band peak
        nowNs += 80_000_000L
        assertTrue(AudioTap.sample(out, nowNs))
        assertTrue(AudioTap.fresh())
        val firstPeak = out.indices.maxByOrNull { out[it] }!!

        // Advance wall clock to 320 ms (past 250 ms burst window, inside second half): must STILL stay live and reflect 4000 Hz!
        nowNs += 240_000_000L
        assertTrue(AudioTap.sample(out, nowNs), "must remain live during 320 ms burst drain")
        val secondPeak = out.indices.maxByOrNull { out[it] }!!
        assertTrue(secondPeak > firstPeak + 10, "delayed read at 320 ms should reflect 4 kHz: first=$firstPeak second=$secondPeak")

        // Stale past latestPlayoutNs + 250 ms -> must go idle
        nowNs += 400_000_000L
        assertFalse(AudioTap.sample(out, nowNs), "stale feed past playout window must return false")
        assertFalse(AudioTap.fresh())
    }

    @Test
    fun pauseResetAndDoPMarkClearLiveSpectrumImmediately() {
        val out = FloatArray(AudioTap.BANDS)
        AudioTap.sample(out, nowNs)
        feed(440.0, 0.2)
        assertTrue(AudioTap.sample(out, nowNs))

        AudioTap.onPause()
        assertFalse(AudioTap.sample(out, nowNs), "onPause must immediately stop live frames")
        assertFalse(AudioTap.fresh())

        feed(440.0, 0.2)
        assertTrue(AudioTap.sample(out, nowNs))
        AudioTap.markNonPcmStream()
        assertFalse(AudioTap.sample(out, nowNs), "DoP/DSD must stay idle")

        feed(440.0, 0.2)
        assertTrue(AudioTap.sample(out, nowNs))
        AudioTap.reset()
        assertFalse(AudioTap.sample(out, nowNs), "reset/flush must clear stale frames")
    }

    @Test
    fun pcmTapReaderDecodesAllFormatsWithoutMutatingBufferPositionOrBytes() {
        val out = FloatArray(AudioTap.BANDS)
        AudioTap.sample(out, nowNs) // set wanted = true

        val formats = listOf(
            PcmTapReader.PCM_16 to 2,
            PcmTapReader.PCM_24 to 3,
            PcmTapReader.PCM_32 to 4,
            PcmTapReader.PCM_FLOAT to 4,
        )
        val rate = 44100
        val frames = 2048
        val ch = 2

        for ((kind, step) in formats) {
            AudioTap.reset()
            AudioTap.sample(out, nowNs)
            val buf = ByteBuffer.allocateDirect(frames * ch * step).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frames) {
                val s = (0.5 * sin(2 * PI * 1000.0 * f / rate)).toFloat()
                repeat(ch) {
                    when (kind) {
                        PcmTapReader.PCM_16 -> buf.putShort((s * 32767f).toInt().toShort())
                        PcmTapReader.PCM_24 -> {
                            val v = (s * 8388607f).toInt()
                            buf.put((v and 0xFF).toByte())
                            buf.put(((v shr 8) and 0xFF).toByte())
                            buf.put(((v shr 16) and 0xFF).toByte())
                        }
                        PcmTapReader.PCM_32 -> buf.putInt((s * 2147483647.0).toInt())
                        PcmTapReader.PCM_FLOAT -> buf.putFloat(s)
                    }
                }
            }
            buf.flip()
            val expectedBytes = ByteArray(buf.remaining())
            buf.duplicate().get(expectedBytes)

            PcmTapReader.feedPcmSlice(buf, 0, buf.remaining(), rate, ch, kind, 0L, "fmt-$kind")
            assertEquals(0, buf.position(), "PcmTapReader must not mutate buffer position")
            assertEquals(expectedBytes.size, buf.limit(), "PcmTapReader must not mutate buffer limit")
            val actualBytes = ByteArray(buf.remaining())
            buf.duplicate().get(actualBytes)
            assertTrue(expectedBytes.contentEquals(actualBytes), "PcmTapReader must leave DAC bytes bit-exact")

            val peak = peakBand()
            assertTrue(peak in 12..19, "1 kHz tone for kind=$kind should peak in mid band, got $peak")
        }
    }

    @Test
    fun spectralOnsetDetectorFiresOnKicksAndStaysZeroOnSilenceAndSteadyTone() {
        val det = SpectralOnsetDetector()
        val silent = FloatArray(AudioTap.BANDS)
        repeat(60) {
            det.update(silent, 0.016f)
            assertEquals(0f, det.onset)
            assertFalse(det.beatHit)
        }

        val steady = FloatArray(AudioTap.BANDS) { if (it == 15) 0.7f else 0.05f }
        // First frame warms up flux; subsequent steady frames must not re-trigger beats
        repeat(30) { det.update(steady, 0.016f) }
        repeat(60) {
            det.update(steady, 0.016f)
            assertFalse(det.beatHit, "steady tone must not trigger repeated beats")
        }

        det.reset()
        var hits = 0
        // Simulate 120 BPM kicks (every 30 frames at 60 fps = 0.5 s)
        for (frame in 0 until 180) {
            val isKick = (frame % 30) in 0..2
            val bands = FloatArray(AudioTap.BANDS) { b ->
                if (isKick && b < 4) 0.9f else 0.05f
            }
            det.update(bands, 0.016f)
            if (det.beatHit) hits++
        }
        assertTrue(hits in 5..7, "expected ~6 beats in 3s at 120 BPM, got $hits")
    }
}
