package io.max.paperplay.core.recs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecMixesTest {
    private fun v(bpm: Float, key: Int, major: Boolean, db: Float) = FloatArray(45).also {
        it[14] = bpm; it[17] = key.toFloat(); it[18] = if (major) 1f else 0f; it[0] = db
    }

    @Test
    fun a_close_tempo_and_key_is_a_smaller_step() {
        val c120 = v(120f, 0, true, -12f)
        assertTrue(RecMixes.step(c120, v(122f, 7, true, -12f)) < RecMixes.step(c120, v(150f, 6, true, -12f)))
        // Double time and the relative minor (A minor next to C major) both mix fine.
        assertTrue(RecMixes.step(c120, v(60f, 0, true, -12f)) < 0.3)
        assertEquals(0.0, RecMixes.step(c120, v(120f, 9, false, -12f)), 1e-9)
    }

    @Test
    fun k_means_separates_two_clear_groups() {
        val a = List(10) { doubleArrayOf(1.0, 0.1 * it, 0.0) }
        val b = List(10) { doubleArrayOf(-1.0, 0.0, 0.1 * it) }
        val c = RecMixes.kMeans(a + b, 2)
        assertEquals(2, c.size)
        assertTrue(c[0][0] * c[1][0] < 0, "one centre per side")
    }
}
