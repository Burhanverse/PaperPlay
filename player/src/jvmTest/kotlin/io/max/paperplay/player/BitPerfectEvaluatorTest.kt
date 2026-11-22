package io.max.paperplay.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class BitPerfectEvaluatorTest {
    private val verifiedUsb = TransportInfo(
        kind = TransportKind.USB_UAC_DIRECT,
        verified = true,
        deviceName = "CX31993",
        confirmedRateHz = 44_100,
        confirmedBitDepth = 24,
        confirmedContainerBits = 32,
        confirmedAlt = 1,
    )

    @Test
    fun `exhaustive 16777216 signed 24-bit int to float32 pow2 round-trip is bit-exact`() {
        for (sample in -8_388_608..8_388_607) {
            val f = BitPerfectEvaluator.int24ToFloat32Pow2(sample)
            val roundTrip = BitPerfectEvaluator.float32ToInt24Pow2(f)
            if (roundTrip != sample) {
                fail("24-bit round-trip mismatch at $sample -> $f -> $roundTrip")
            }
        }
    }

    @Test
    fun `24-bit float pow2 to 24-bit and 32-bit int passes, 32-bit int via float fails`() {
        val f24 = PcmFormat(44_100, 24, 32, 32, SampleEncoding.PCM_FLOAT, 2, exactPowerOfTwoFloat = true)
        val out24 = PcmFormat(44_100, 24, 24, 24, SampleEncoding.PCM_INT, 2)
        val out32 = PcmFormat(44_100, 24, 24, 32, SampleEncoding.PCM_INT, 2)
        assertTrue(BitPerfectEvaluator.evaluate(verifiedUsb, f24, out24, emptyList()).bitPerfect)
        assertTrue(BitPerfectEvaluator.evaluate(verifiedUsb, f24, out32, emptyList()).bitPerfect)

        val f32Src = PcmFormat(44_100, 32, 32, 32, SampleEncoding.PCM_FLOAT, 2, exactPowerOfTwoFloat = true)
        val out32Int = PcmFormat(44_100, 32, 32, 32, SampleEncoding.PCM_INT, 2)
        val v = BitPerfectEvaluator.evaluate(verifiedUsb, f32Src, out32Int, emptyList())
        assertFalse(v.bitPerfect)
        assertTrue(v.note.contains(">24-bit"))
    }

    @Test
    fun `lossless zero-padded integer widening passes while truncation or non-pow2 float fails`() {
        val s16 = PcmFormat(48_000, 16, 16, 16, SampleEncoding.PCM_INT, 2)
        val out24 = PcmFormat(48_000, 16, 24, 24, SampleEncoding.PCM_INT, 2)
        val out32 = PcmFormat(48_000, 16, 24, 32, SampleEncoding.PCM_INT, 2)
        assertTrue(BitPerfectEvaluator.evaluate(verifiedUsb, s16, out24, emptyList()).bitPerfect)
        assertTrue(BitPerfectEvaluator.evaluate(verifiedUsb, s16, out32, emptyList()).bitPerfect)

        val s24 = PcmFormat(48_000, 24, 24, 24, SampleEncoding.PCM_INT, 2)
        val out16 = PcmFormat(48_000, 24, 16, 16, SampleEncoding.PCM_INT, 2)
        assertFalse(BitPerfectEvaluator.evaluate(verifiedUsb, s24, out16, emptyList()).bitPerfect)

        val nonPow2 = PcmFormat(48_000, 24, 32, 32, SampleEncoding.PCM_FLOAT, 2, exactPowerOfTwoFloat = false)
        assertFalse(BitPerfectEvaluator.evaluate(verifiedUsb, nonPow2, out32, emptyList()).bitPerfect)
    }

    @Test
    fun `dop 24 passes at matching rate and fails on rate mismatch or pcm output`() {
        val dop = PcmFormat(176_400, 24, 24, 32, SampleEncoding.DOP_24, 2)
        assertTrue(BitPerfectEvaluator.evaluate(verifiedUsb, dop, dop, emptyList()).bitPerfect)

        val dopOtherRate = dop.copy(sampleRateHz = 88_200)
        assertFalse(BitPerfectEvaluator.evaluate(verifiedUsb, dop, dopOtherRate, emptyList()).bitPerfect)

        val pcmOut = dop.copy(encoding = SampleEncoding.PCM_INT)
        assertFalse(BitPerfectEvaluator.evaluate(verifiedUsb, dop, pcmOut, emptyList()).bitPerfect)
    }

    @Test
    fun `speed, effects, rate mismatch, and unverified mixer all fail bit-perfect`() {
        val pcm = PcmFormat(44_100, 16, 16, 16, SampleEncoding.PCM_INT, 2)
        val speedV = BitPerfectEvaluator.evaluate(verifiedUsb, pcm, pcm, listOf("Speed 1.25x"))
        assertFalse(speedV.bitPerfect)
        assertEquals("Not bit-perfect: speed 1.25x", speedV.note)

        val eqV = BitPerfectEvaluator.evaluate(verifiedUsb, pcm, pcm, listOf("Equalizer (engine)"))
        assertFalse(eqV.bitPerfect)
        assertTrue(eqV.note.contains("Equalizer"))

        val rateMismatch = pcm.copy(sampleRateHz = 48_000)
        assertFalse(BitPerfectEvaluator.evaluate(verifiedUsb, pcm, rateMismatch, emptyList()).bitPerfect)

        val mixer = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false)
        assertFalse(BitPerfectEvaluator.evaluate(mixer, pcm, pcm, emptyList()).bitPerfect)
    }

    @Test
    fun `allowEqInUsbExclusive distinguishes strict bit-perfect from USB Direct plus EQ honestly`() {
        val pcm96 = PcmFormat(96_000, 24, 24, 24, SampleEncoding.PCM_INT, 2)
        val usb96 = verifiedUsb.copy(confirmedRateHz = 96_000, confirmedBitDepth = 24)

        // EQ off -> Bit-Perfect active
        val offV = BitPerfectEvaluator.evaluate(usb96, pcm96, pcm96, emptyList(), requestedOn = true, allowEqInUsbExclusive = true)
        assertTrue(offV.bitPerfect)
        assertFalse(offV.directWithEq)

        // EQ on + allowEqInUsbExclusive = true -> USB Direct + EQ (never claims Bit-Perfect)
        val directEqV = BitPerfectEvaluator.evaluate(
            usb96, pcm96, pcm96, listOf("Parametric EQ (10 bands)"), requestedOn = true, allowEqInUsbExclusive = true,
        )
        assertFalse(directEqV.bitPerfect)
        assertTrue(directEqV.directWithEq)
        assertTrue(directEqV.note.contains("USB Direct + EQ"))

        // EQ on + allowEqInUsbExclusive = false -> rejected as non-bit-perfect (strict mode bypasses EQ)
        val strictV = BitPerfectEvaluator.evaluate(
            usb96, pcm96, pcm96, listOf("Parametric EQ (10 bands)"), requestedOn = true, allowEqInUsbExclusive = false,
        )
        assertFalse(strictV.bitPerfect)
        assertFalse(strictV.directWithEq)

        // Non-EQ effect (e.g. Normalise) is never treated as Direct+EQ
        val normV = BitPerfectEvaluator.evaluate(
            usb96, pcm96, pcm96, listOf("Parametric EQ (10 bands)", "Normalise"), requestedOn = true, allowEqInUsbExclusive = true,
        )
        assertFalse(normV.bitPerfect)
        assertFalse(normV.directWithEq)
    }
}
