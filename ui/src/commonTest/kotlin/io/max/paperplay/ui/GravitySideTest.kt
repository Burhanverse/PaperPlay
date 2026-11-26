package io.max.paperplay.ui

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GravitySideTest {
    /** Gravity of a phone held at [turnDeg] (0 upright, 90 on its left side) and reclined [reclineDeg] from upright. */
    private fun side(turnDeg: Double, reclineDeg: Double, current: Int?): Int? {
        val inPlane = 9.81 * cos(reclineDeg * kotlin.math.PI / 180)
        return gravitySide((inPlane * sin(turnDeg * kotlin.math.PI / 180)).toFloat(), (inPlane * cos(turnDeg * kotlin.math.PI / 180)).toFloat(), current)
    }

    @Test
    fun aRecliningPhoneStillTurns() {
        // On a stand 60 degrees back the old rule (6.5 m/s² on one axis) saw nothing.
        assertEquals(1, side(90.0, 60.0, 0))
        assertEquals(3, side(-90.0, 60.0, 0))
        assertEquals(0, side(0.0, 60.0, 1))
        assertEquals(2, side(180.0, 20.0, 0))
    }

    @Test
    fun flatOrNearHalfwayKeepsTheSide() {
        assertNull(side(90.0, 85.0, 0))
        // Held at 50 degrees: still the side it was, both ways.
        assertNull(side(50.0, 10.0, 0))
        assertNull(side(40.0, 10.0, 1))
        assertEquals(1, side(70.0, 10.0, 0))
        // Nothing shown yet: the nearest side.
        assertEquals(1, side(50.0, 10.0, null))
    }
}
