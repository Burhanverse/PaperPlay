package io.max.paperplay.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays real files through the real libmpv with its silent output (`ao=null`), so nothing is heard. Skipped when the library is
 * not next to the app (it is not in git; see HANDOVER.md).
 */
class MpvPlayerEngineTest {
    private val lib = MpvLoader.lib
    private var engine: MpvPlayerEngine? = null
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("mpvtest", "").apply { delete(); mkdirs() }
        lib?.let { engine = MpvPlayerEngine(it, mapOf("ao" to "null")) }
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
        dir.deleteRecursively()
    }

    private fun wav(name: String, rate: Int, seconds: Double, hz: Double, bits: Int = 16): String {
        val frames = (rate * seconds).toInt()
        val bytes = bits / 8
        val data = ByteBuffer.allocate(frames * 2 * bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = sin(2 * PI * hz * i / rate) * 0.3
            repeat(2) {
                if (bits == 16) data.putShort((v * 32767).toInt().toShort())
                else { val s = (v * 8388607).toInt(); data.put(s.toByte()).put((s shr 8).toByte()).put((s shr 16).toByte()) }
            }
        }
        val out = ByteArrayOutputStream()
        fun w32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun w16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); w32(36 + data.capacity()); out.write("WAVEfmt ".toByteArray())
        w32(16); w16(1); w16(2); w32(rate); w32(rate * 2 * bytes); w16(2 * bytes); w16(bits)
        out.write("data".toByteArray()); w32(data.capacity()); out.write(data.array())
        return File(dir, name).apply { writeBytes(out.toByteArray()) }.absolutePath
    }

    private fun waitFor(ms: Long = 15_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `plays a file and reports its format`() {
        val e = engine ?: return
        e.setQueue(listOf(QueueItem(wav("a.wav", 44100, 2.0, 440.0))))
        waitFor(what = "playing") { e.state.value == PlayerState.Playing }
        waitFor(what = "duration") { e.durationMs.value in 1_900..2_100 }
        waitFor(what = "position") { e.positionMs.value > 300 }
        waitFor(what = "info") { e.outputInfo.value?.sampleRateHz == 44100 }
        assertEquals(2, e.outputInfo.value?.channels)
        assertEquals(0, e.currentIndex.value)
    }

    @Test
    fun `goes from one song to the next and ends after the last`() {
        val e = engine ?: return
        e.setQueue(listOf(
            QueueItem(wav("a.wav", 44100, 1.2, 440.0)),
            QueueItem(wav("b.wav", 48000, 1.2, 660.0)),
            QueueItem(wav("c.wav", 44100, 1.2, 880.0, bits = 24)),
        ))
        val seen = LinkedHashSet<Int>()
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && e.state.value != PlayerState.Ended) {
            seen.add(e.currentIndex.value)
            Thread.sleep(10)
        }
        assertEquals(PlayerState.Ended, e.state.value, "the queue should end; seen $seen")
        assertEquals(setOf(0, 1, 2), seen.filter { it >= 0 }.toSet())
    }

    @Test
    fun `seek, pause and play work and next and previous move through the queue`() {
        val e = engine ?: return
        e.setQueue(listOf(QueueItem(wav("a.wav", 44100, 5.0, 440.0)), QueueItem(wav("b.wav", 44100, 5.0, 660.0))))
        waitFor(what = "playing") { e.state.value == PlayerState.Playing }
        e.seek(3_000)
        waitFor(what = "seek") { e.positionMs.value in 2_900..4_500 }
        e.pause()
        waitFor(what = "paused") { e.state.value == PlayerState.Paused }
        val held = e.positionMs.value
        Thread.sleep(400)
        assertTrue(kotlin.math.abs(e.positionMs.value - held) < 250, "paused position should hold")
        e.play()
        waitFor(what = "playing again") { e.state.value == PlayerState.Playing }
        e.next()
        waitFor(what = "next") { e.currentIndex.value == 1 && e.state.value == PlayerState.Playing }
        e.seek(0)
        waitFor(what = "restart") { e.positionMs.value < 1_500 }
        e.previous()
        waitFor(what = "previous") { e.currentIndex.value == 0 }
    }

    @Test
    fun `the equalizer, bass, a profile, normalise, speed and volume are accepted by mpv`() {
        val e = engine ?: return
        e.setQueue(listOf(QueueItem(wav("a.wav", 48000, 4.0, 220.0))))
        waitFor(what = "playing") { e.state.value == PlayerState.Playing }
        e.setEqualizer(listOf(12f, 10f, 5f, 0f, -1f, 0f, 2f, 0f, -3f, 0f))
        e.setBass(9f)
        e.setCorrectionProfile(CorrectionProfile.MOONDROP_CHU_2)
        e.setNormalize(true)
        e.setSpeed(1.25f)
        e.setVolume(0.8f)
        waitFor(what = "effects listed") {
            val fx = e.outputInfo.value?.effects.orEmpty()
            "Equalizer" in fx && fx.any { it.startsWith("Bass") } && "Correction profile" in fx && "Normalise" in fx
        }
        assertEquals(0, e.filterResult, "mpv must accept the filter graph (${e.lastGraph})")
        val pos = e.positionMs.value
        waitFor(what = "still playing with the filters") { e.positionMs.value > pos + 300 && e.state.value == PlayerState.Playing }
        e.setEqualizer(null); e.setBass(0f); e.setCorrectionProfile(null); e.setNormalize(false)
        waitFor(what = "filters cleared") { e.outputInfo.value?.effects?.none { it == "Equalizer" || it.startsWith("Bass") } == true }
        assertEquals(0, e.filterResult)
    }

    @Test
    fun `a seek made before the song has loaded is applied once it has`() {
        val e = engine ?: return
        e.restoreQueue(listOf(QueueItem(wav("r.wav", 44100, 6.0, 440.0))), 0, 3_000)
        waitFor(what = "paused at the restored place") { e.state.value == PlayerState.Paused && e.positionMs.value in 2_800..3_600 }
    }

    @Test
    fun `a missing file ends in an error and does not hang the engine`() {
        val e = engine ?: return
        e.setQueue(listOf(QueueItem(File(dir, "nothing.wav").absolutePath)))
        waitFor(what = "error") { e.state.value == PlayerState.Error }
        e.setQueue(listOf(QueueItem(wav("ok.wav", 44100, 1.5, 440.0))))
        waitFor(what = "recovery") { e.state.value == PlayerState.Playing }
    }

    @Test
    fun `exclusive output can be switched on and off without breaking playback`() {
        val e = engine ?: return
        e.setQueue(listOf(QueueItem(wav("a.wav", 44100, 4.0, 330.0))))
        waitFor(what = "playing") { e.state.value == PlayerState.Playing }
        e.setExclusiveOutput(true)
        e.setExclusiveOutput(false)
        val pos = e.positionMs.value
        waitFor(what = "still playing") { e.positionMs.value > pos + 200 }
    }

    // ---- offline rendering: the real filter chain, written to a WAV file and measured

    /** A two-tone WAV: [a] Hz at [ampA] plus [b] Hz at [ampB]. */
    private fun twoTone(name: String, rate: Int, seconds: Double, a: Double, ampA: Double, b: Double, ampB: Double): String {
        val frames = (rate * seconds).toInt()
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = ampA * sin(2 * PI * a * i / rate) + ampB * sin(2 * PI * b * i / rate)
            val q = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            data.putShort(q); data.putShort(q)
        }
        val out = ByteArrayOutputStream()
        fun w32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun w16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); w32(36 + data.capacity()); out.write("WAVEfmt ".toByteArray())
        w32(16); w16(1); w16(2); w32(rate); w32(rate * 4); w16(4); w16(16)
        out.write("data".toByteArray()); w32(data.capacity()); out.write(data.array())
        return File(dir, name).apply { writeBytes(out.toByteArray()) }.absolutePath
    }

    /** Plays [input] with the given sound settings into a WAV file (mpv's pcm output) and returns the left channel and the rate. */
    private fun render(input: String, eq: List<Float>?, bass: Float, channel: Int = 0, effects: ((MpvPlayerEngine) -> Unit)? = null): Pair<FloatArray, Int> {
        val l = lib ?: error("no libmpv")
        val outFile = File(dir, "out-${System.nanoTime()}.wav")
        val e = MpvPlayerEngine(l, mapOf("ao" to "pcm", "ao-pcm-file" to outFile.absolutePath, "ao-pcm-waveheader" to "yes"))
        try {
            e.setEqualizer(eq); e.setBass(bass); effects?.invoke(e)
            waitFor(what = "filters set") { e.filterResult == 0 || (eq == null && bass == 0f && effects == null) }
            e.setQueue(listOf(QueueItem(input)))
            waitFor(what = "played to the end", ms = 30_000) { e.state.value == PlayerState.Ended }
        } finally { e.release() }
        val b = ByteBuffer.wrap(outFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var tag = 1; var ch = 2; var rate = 44100; var bits = 16
        while (pos + 8 <= b.capacity()) {
            val id = String(ByteArray(4) { b.get(pos + it) })
            val size = b.getInt(pos + 4)
            if (id == "fmt ") { tag = b.getShort(pos + 8).toInt(); ch = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt() }
            if (id == "data") {
                val start = pos + 8
                val end = if (size <= 0 || start + size > b.capacity()) b.capacity() else start + size
                val bytes = bits / 8
                val n = (end - start) / (bytes * ch)
                return FloatArray(n) { i ->
                    val o = start + i * bytes * ch + channel * bytes
                    when {
                        tag == 3 && bits == 32 -> b.getFloat(o)
                        bits == 16 -> b.getShort(o) / 32768f
                        bits == 24 -> (((b.get(o).toInt() and 0xff) or ((b.get(o + 1).toInt() and 0xff) shl 8) or (b.get(o + 2).toInt() shl 16)) / 8388608f)
                        else -> b.getInt(o) / 2147483648f
                    }
                } to rate
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in the rendered file")
    }

    private fun amp(x: FloatArray, from: Int, hz: Double, rate: Int): Double {
        var re = 0.0; var im = 0.0
        val n = x.size - from
        for (i in 0 until n) { val ph = 2 * PI * hz * (from + i) / rate; re += x[from + i] * kotlin.math.cos(ph); im += x[from + i] * sin(ph) }
        return 2 * kotlin.math.sqrt(re * re + im * im) / n
    }

    private fun db(r: Double) = 20 * kotlin.math.log10(r)

    @Test
    fun `bass makes the low tone clearly stronger than the mids and never clips`() {
        if (lib == null) return
        val src = twoTone("bm.wav", 48000, 4.0, 55.0, 0.5, 1000.0, 0.2)
        val (plain, r0) = render(src, null, 0f)
        val (boosted, r1) = render(src, null, 9f)
        val from = r0 * 2
        val before = amp(plain, from, 55.0, r0) / amp(plain, from, 1000.0, r0)
        val after = amp(boosted, r1 * 2, 55.0, r1) / amp(boosted, r1 * 2, 1000.0, r1)
        assertTrue(db(after / before) > 8.0, "bass gained only ${db(after / before)} dB over the mids")
        assertTrue(boosted.all { kotlin.math.abs(it) <= 1.0f }, "the output must not clip")
    }

    @Test
    fun `a loud bass does not pull a quiet high tone down`() {
        if (lib == null) return
        val eq = listOf(12f, 10f, 5f, 0f, -1f, 0f, 0f, 0f, 0f, 0f)
        val mix = twoTone("mix.wav", 48000, 4.0, 60.0, 0.8, 3000.0, 0.1)
        val high = twoTone("high.wav", 48000, 4.0, 60.0, 0.0, 3000.0, 0.1)
        val (a, ra) = render(mix, eq, 12f)
        val (b, rb) = render(high, eq, 12f)
        val d = db(amp(a, ra * 2, 3000.0, ra) / amp(b, rb * 2, 3000.0, rb))
        assertTrue(kotlin.math.abs(d) < 1.0, "the bass moved the 3 kHz tone by $d dB")
        assertTrue(a.all { kotlin.math.abs(it) <= 1.0f })
    }

    /** A stereo WAV with a tone in the left channel only (the right one is silent), to see what crosses over. */
    private fun leftOnlyTone(name: String, rate: Int, seconds: Double, hz: Double, amp: Double): String {
        val frames = (rate * seconds).toInt()
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            data.putShort((amp * sin(2 * PI * hz * i / rate) * 32767).toInt().toShort()); data.putShort(0)
        }
        val out = ByteArrayOutputStream()
        fun w32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun w16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); w32(36 + data.capacity()); out.write("WAVEfmt ".toByteArray())
        w32(16); w16(1); w16(2); w32(rate); w32(rate * 4); w16(4); w16(16)
        out.write("data".toByteArray()); w32(data.capacity()); out.write(data.array())
        return File(dir, name).apply { writeBytes(out.toByteArray()) }.absolutePath
    }

    @Test
    fun `crossfeed lets some of a hard left sound reach the right ear, and off it stays silent`() {
        if (lib == null) return
        val src = leftOnlyTone("lt.wav", 48000, 4.0, 300.0, 0.5)
        val (plainR, r0) = render(src, null, 0f, channel = 1)
        val (fedR, r1) = render(src, null, 0f, channel = 1, effects = { it.setEngineOptions(4) })
        val plain = amp(plainR, r0 * 2, 300.0, r0)
        val fed = amp(fedR, r1 * 2, 300.0, r1)
        assertTrue(plain < 0.001, "without crossfeed the right channel must stay silent, got $plain")
        assertTrue(fed > 0.02, "crossfeed should put some of the left tone in the right channel, got $fed")
        assertTrue(fedR.all { kotlin.math.abs(it) <= 1.0f })
    }

    @Test
    fun `a wider image puts more side sound in, a narrower one less, without clipping`() {
        if (lib == null) return
        val src = leftOnlyTone("lw.wav", 48000, 4.0, 300.0, 0.5)
        fun right(width: Float): Double {
            val (r, rate) = render(src, null, 0f, channel = 1, effects = { it.setEngineOptions(4); it.setStereoWidth(width) })
            assertTrue(r.all { kotlin.math.abs(it) <= 1.0f }, "clipped at width $width")
            return amp(r, rate * 2, 300.0, rate)
        }
        assertTrue(right(0.5f) != right(1.5f), "width had no effect")
    }
}
