package io.max.paperplay.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies real audio tap feeding on desktop engines (`MpvPlayerEngine` and `JvmPlayerEngine`/vlcj)
 * using generated beat, steady-tone, and silence WAV fixtures.
 */
class DesktopAudioTapTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("desktoptap", "").apply { delete(); mkdirs() }
        AudioTap.clockNs = { System.nanoTime() }
        AudioTap.calibrationOffsetMs = 0L
        AudioTap.reset()
    }

    @AfterTest
    fun tearDown() {
        AudioTap.reset()
        dir.deleteRecursively()
    }

    private fun writeWav(name: String, rate: Int, seconds: Double, sampleFn: (Int) -> Double): String {
        val frames = (rate * seconds).toInt()
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val q = (sampleFn(i).coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
            data.putShort(q)
            data.putShort(q)
        }
        val out = ByteArrayOutputStream()
        fun w32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun w16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); w32(36 + data.capacity()); out.write("WAVEfmt ".toByteArray())
        w32(16); w16(1); w16(2); w32(rate); w32(rate * 4); w16(4); w16(16)
        out.write("data".toByteArray()); w32(data.capacity()); out.write(data.array())
        return File(dir, name).apply { writeBytes(out.toByteArray()) }.absolutePath
    }

    private fun waitFor(ms: Long = 12_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun mpvFeedsRealSpectrumWhenWantedAndStaysIdleWhenNotWantedOrSilent() {
        val lib = MpvLoader.lib ?: run {
            println("SKIPPED: libmpv not available")
            return
        }
        val beatWav = writeWav("beat.wav", 44100, 3.0) { i ->
            val t = i / 44100.0
            val beatPhase = (t * 2.0) % 1.0 // 120 BPM
            val env = exp(-beatPhase * 12.0)
            0.7 * env * sin(2 * PI * 65.0 * t)
        }
        val silenceWav = writeWav("silence.wav", 44100, 2.0) { 0.0 }

        val engine = MpvPlayerEngine(lib, mapOf("ao" to "null"))
        try {
            // 1. Not wanted -> zero shadow tap pushes
            AudioTap.reset()
            engine.setQueue(listOf(QueueItem(beatWav)))
            waitFor(what = "mpv playing") { engine.state.value == PlayerState.Playing }
            Thread.sleep(350)
            assertFalse(AudioTap.wanted)
            assertTrue(AudioTap.pushSampleCount == 0L, "must not push when Standby is not shown, got ${AudioTap.pushSampleCount}")

            // 2. Wanted -> receives live beat spectrum
            val out = FloatArray(AudioTap.BANDS)
            AudioTap.sample(out) // mark wanted
            var maxBass = 0f
            var liveFrames = 0
            val deadline = System.currentTimeMillis() + 1800
            while (System.currentTimeMillis() < deadline) {
                if (AudioTap.sample(out)) {
                    liveFrames++
                    val bass = (out[0] + out[1] + out[2] + out[3]) / 4f
                    if (bass > maxBass) maxBass = bass
                }
                Thread.sleep(16)
            }
            println("mpv beat tap: source=${AudioTap.activeSourceTag} pushes=${AudioTap.pushSampleCount} ffts=${AudioTap.fftFrameCount} liveFrames=$liveFrames maxBass=$maxBass")
            assertTrue(liveFrames > 5, "expected live frames from mpv, got $liveFrames")
            assertTrue(maxBass > 0.15f, "expected bass energy from 65 Hz kick, got $maxBass")

            // 3. Pause stops live feed
            engine.pause()
            waitFor(what = "mpv paused") { engine.state.value == PlayerState.Paused }
            assertFalse(AudioTap.sample(out), "paused mpv must clear live feed")

            // 4. Silence stays flat
            AudioTap.reset()
            AudioTap.sample(out)
            engine.setQueue(listOf(QueueItem(silenceWav)))
            waitFor(what = "mpv playing silence") { engine.state.value == PlayerState.Playing }
            Thread.sleep(500)
            AudioTap.sample(out)
            assertTrue(out.all { it < 0.02f }, "silence must stay flat, got ${out.toList()}")
        } finally {
            engine.release()
        }
    }

    @Test
    fun vlcjFeedsRealSpectrumWhenWantedAndStopsOnPause() {
        val engine = try {
            JvmPlayerEngine("--aout=dummy")
        } catch (e: Throwable) {
            println("SKIPPED: libvlc not available (${e.message})")
            return
        }
        val toneWav = writeWav("tone1k.wav", 44100, 3.0) { i ->
            0.5 * sin(2 * PI * 1000.0 * i / 44100.0)
        }
        try {
            val out = FloatArray(AudioTap.BANDS)
            AudioTap.sample(out) // mark wanted
            engine.setQueue(listOf(QueueItem(toneWav)))
            waitFor(what = "vlc playing") { engine.state.value == PlayerState.Playing }

            var liveFrames = 0
            var peakBand = -1
            val deadline = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < deadline) {
                if (AudioTap.sample(out)) {
                    liveFrames++
                    peakBand = out.indices.maxByOrNull { out[it] } ?: -1
                }
                Thread.sleep(20)
            }
            println("vlcj tone tap: source=${AudioTap.activeSourceTag} pushes=${AudioTap.pushSampleCount} ffts=${AudioTap.fftFrameCount} liveFrames=$liveFrames peakBand=$peakBand")
            assertTrue(liveFrames > 5, "expected live frames from vlcj, got $liveFrames")
            assertTrue(peakBand in 11..20, "1 kHz tone should peak in mid band, got $peakBand")

            engine.pause()
            waitFor(what = "vlc paused") { engine.state.value == PlayerState.Paused }
            assertFalse(AudioTap.sample(out), "paused vlcj must clear live feed")
        } finally {
            engine.release()
        }
    }
}
