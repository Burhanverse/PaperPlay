package io.max.paperplay.ui.theme

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionSystemTest {
    @Test
    fun spatialSpringsAreCriticallyDampedWithZeroOvershoot() {
        val stiffnesses = listOf(
            Motion.Spatial.EnterStiffness,
            Motion.Spatial.ExitStiffness,
            Motion.Spatial.SettleStiffness,
            Motion.Spatial.MoveStiffness,
        )
        for (k in stiffnesses) {
            val vec = spring<Float>(
                dampingRatio = Motion.Spatial.DampingNoBounce,
                stiffness = k,
                visibilityThreshold = 0.001f,
            ).vectorize(Float.VectorConverter)
            val durNs = vec.getDurationNanos(AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f))
            var t = 0L
            while (t <= durNs) {
                val v = vec.getValueFromNanos(t, AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)).value
                assertTrue(v in -1e-4f..(1f + 1e-4f), "Spatial spring k=$k overshot: $v")
                t += 4_000_000L
            }
        }
    }

    @Test
    fun ambientClockIsRefreshRateIndependentAcross60_90_120Hz() {
        val c60 = AmbientClock()
        val c90 = AmbientClock()
        val c120 = AmbientClock()

        // Advance each for exactly 1.0 second at 60, 90, 120 Hz steps
        repeat(60) { c60.advance(1f / 60f) }
        repeat(90) { c90.advance(1f / 90f) }
        repeat(120) { c120.advance(1f / 120f) }

        assertEquals(1f, c60.seconds, 1e-3f)
        assertEquals(1f, c90.seconds, 1e-3f)
        assertEquals(1f, c120.seconds, 1e-3f)
        assertEquals(c60.phase(1300), c120.phase(1300), 1e-3f)

        for (step in 0..40) {
            c60.advance(0.05f)
            assertTrue(c60.phase(1000) in 0f..1f)
            assertTrue(c60.pingPong(700) in 0f..1f)
        }
    }

    @Test
    fun motionTuningScalesAndClampsSafely() {
        val t = MotionTuning(durationScale = 1.5f, spatialStiffnessScale = 0.8f)
        assertEquals(450, t.scaledMs(300))
        assertEquals(400f, t.scaledStiffness(500f), 1e-3f)

        val extreme = MotionTuning(durationScale = 0.01f, spatialStiffnessScale = 0.01f)
        assertEquals(75, extreme.scaledMs(300))
        assertEquals(200f, extreme.scaledStiffness(500f), 1e-3f)
    }

    @Test
    fun pageBackTransformRespectsReduceMotionAndBounds() {
        val normal = io.max.paperplay.ui.app.pageBackTransform(0.5f, 1000f, reduceMotion = false)
        assertTrue(normal.translationX > 0f)
        assertTrue(normal.scale in 0.9f..1f)
        assertTrue(normal.alpha in 0.7f..1f)

        val reduced = io.max.paperplay.ui.app.pageBackTransform(0.5f, 1000f, reduceMotion = true)
        assertEquals(0f, reduced.translationX)
        assertEquals(1f, reduced.scale)
        assertTrue(reduced.alpha < 1f)
    }
}
