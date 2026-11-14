package io.max.paperplay.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class AudioTapTest {
    private fun feed(hz: Double, seconds: Double, amp: Float = 0.5f) {
        val rate = 44100
        for (i in 0 until (rate * seconds).toInt()) AudioTap.push(rate, (amp * sin(2 * PI * hz * i / rate)).toFloat())
    }

    private fun peakBand(): Int = AudioTap.bands.indices.maxByOrNull { AudioTap.bands[it] }!!

    @Test fun silenceStaysFlat() {
        AudioTap.reset()
        repeat(44100) { AudioTap.push(44100, 0f) }
        assertTrue(AudioTap.bands.all { it < 0.05f }, "silence should show nothing: ${AudioTap.bands.toList()}")
    }

    @Test fun aToneLightsItsOwnBandAndMovesWithPitch() {
        AudioTap.reset(); feed(200.0, 0.5)
        val low = peakBand()
        AudioTap.reset(); feed(3000.0, 0.5)
        val high = peakBand()
        assertTrue(high > low + 8, "3 kHz should sit well above 200 Hz: low=$low high=$high")
    }

    @Test fun differentSoundsGiveDifferentPictures() {
        AudioTap.reset(); feed(100.0, 0.5)
        val a = AudioTap.bands.copyOf()
        AudioTap.reset(); feed(5000.0, 0.5)
        val b = AudioTap.bands.copyOf()
        assertTrue(a.indices.sumOf { kotlin.math.abs(a[it] - b[it]).toDouble() } > 3.0, "the spectrum must depend on the audio")
    }
}
