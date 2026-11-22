package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsbVolumeCurveTest {

    @Test
    fun testAudiocularD07Curve() {
        // Audiocular D07: min = -74.0 dB, max = 0.0 dB, res = 0.5 dB, 16 steps
        val maxIndex = 16
        val minDb = -74.0f
        val maxDb = 0.0f
        val resDb = 0.5f

        val table = (0..maxIndex).map { i ->
            UsbVolumeCurve.computeVolumeDb(i, maxIndex, minDb, maxDb, resDb)
        }

        println("Audiocular D07 (N=16) volume table:")
        table.forEachIndexed { i, db ->
            val step = if (i > 1) " (step: +${db - table[i - 1]} dB)" else ""
            println("  [$i]: $db dB$step")
        }

        // 1. Endpoint checks
        assertEquals(Float.NEGATIVE_INFINITY, table[0], "Index 0 must be MUTE")
        assertEquals(0.0f, table[maxIndex], "Index maxIndex must reach exact 0.0 dB")
        assertEquals(-52.5f, table[1], "Index 1 should start at safe floor near -50 to -55 dB")

        // 2. Strict monotonicity for indices 1..maxIndex
        for (i in 1 until maxIndex) {
            assertTrue(table[i + 1] > table[i], "Table must be strictly increasing at index $i -> ${i + 1}")
        }

        // 3. Step size checks: average step size <= 3.5 dB
        for (i in 1 until maxIndex) {
            val step = table[i + 1] - table[i]
            assertTrue(step in 2.5f..3.6f, "Step from $i to ${i + 1} was $step dB (expected <= ~3.5 dB)")
        }
    }

    @Test
    fun testAudiocularD07With15Steps() {
        val maxIndex = 15
        val minDb = -74.0f
        val maxDb = 0.0f
        val resDb = 0.5f

        val table = (0..maxIndex).map { i ->
            UsbVolumeCurve.computeVolumeDb(i, maxIndex, minDb, maxDb, resDb)
        }

        assertEquals(Float.NEGATIVE_INFINITY, table[0])
        assertEquals(0.0f, table[maxIndex])
        for (i in 1 until maxIndex) {
            assertTrue(table[i + 1] > table[i])
            val step = table[i + 1] - table[i]
            assertTrue(step <= 3.6f, "Step was $step dB")
        }
    }

    @Test
    fun testFineVolumeCurve25Steps() {
        val maxIndex = 25
        val minDb = -74.0f
        val maxDb = 0.0f
        val resDb = 0.5f

        val table = (0..maxIndex).map { i ->
            UsbVolumeCurve.computeVolumeDb(i, maxIndex, minDb, maxDb, resDb)
        }

        assertEquals(Float.NEGATIVE_INFINITY, table[0])
        assertEquals(0.0f, table[maxIndex])
        assertEquals(-60.0f, table[1], "Floor on 25 steps should be -60 dB")

        for (i in 1 until maxIndex) {
            assertTrue(table[i + 1] > table[i])
            val step = table[i + 1] - table[i]
            assertTrue(step in 2.0f..3.5f, "Step was $step dB")
        }
    }

    @Test
    fun testSlewLimiterAutoIncreaseClamped() {
        // Automatic increase of +8 steps (index 8 -> 16) clamped to 9 (+1 step)
        val clamped = UsbVolumeCurve.applySlewLimiter(
            requestedIndex = 16,
            lastAppliedIndex = 8,
            maxIndex = 16,
            isUser = false
        )
        assertEquals(9, clamped, "Automatic jump from 8 to 16 must be clamped to 8 + 1 = 9")

        // Automatic increase of 1 step (index 8 -> 9) allowed as-is
        val step1 = UsbVolumeCurve.applySlewLimiter(
            requestedIndex = 9,
            lastAppliedIndex = 8,
            maxIndex = 16,
            isUser = false
        )
        assertEquals(9, step1)
    }

    @Test
    fun testSlewLimiterUserIncreaseNotClamped() {
        // Explicit user action from slider / buttons applies immediately
        val userJump = UsbVolumeCurve.applySlewLimiter(
            requestedIndex = 16,
            lastAppliedIndex = 8,
            maxIndex = 16,
            isUser = true
        )
        assertEquals(16, userJump, "User action must apply immediately without slew limiting")
    }

    @Test
    fun testSlewLimiterDecreaseAlwaysImmediate() {
        // Volume decrease must NEVER be slew limited (safety first)
        val autoDecrease = UsbVolumeCurve.applySlewLimiter(
            requestedIndex = 2,
            lastAppliedIndex = 16,
            maxIndex = 16,
            isUser = false
        )
        assertEquals(2, autoDecrease, "Volume decrease must apply immediately for safety")

        val userDecrease = UsbVolumeCurve.applySlewLimiter(
            requestedIndex = 0,
            lastAppliedIndex = 16,
            maxIndex = 16,
            isUser = true
        )
        assertEquals(0, userDecrease)
    }

    @Test
    fun testIndex0MutesHardwareConversion() {
        val minRaw: Short = -18944 // -74.0 dB
        val maxRaw: Short = 0      // 0.0 dB
        val resRaw: Short = 128    // 0.5 dB

        val db0 = UsbVolumeCurve.computeVolumeDb(
            index = 0,
            maxIndex = 16,
            dacMinDb = -74.0f,
            dacMaxDb = 0.0f,
            dacResDb = 0.5f
        )
        assertEquals(Float.NEGATIVE_INFINITY, db0)

        val raw0 = UsbVolumeCurve.dbToRaw(db0, minRaw, maxRaw, resRaw)
        assertEquals(minRaw, raw0, "Mute must convert to minRaw hardware volume")
    }
}
