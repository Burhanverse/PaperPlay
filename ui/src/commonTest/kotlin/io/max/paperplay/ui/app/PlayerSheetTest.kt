package io.max.paperplay.ui.app

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.MonotonicFrameClock
import io.max.paperplay.ui.theme.sheetCloseSpringSpec
import io.max.paperplay.ui.theme.sheetOpenSpringSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SteppingFrameClock(private val stepNs: Long = 16_000_000L) : MonotonicFrameClock {
    private var nowNs = 0L
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        nowNs += stepNs
        return onFrame(nowNs)
    }
}

class PlayerSheetTest {
    @Test
    fun releaseClosesPastDistanceThresholdAndNeverOvershoots() = runBlocking {
        withContext(SteppingFrameClock()) {
            val sheet = PlayerSheetState()
            sheet.progress.snapTo(1f)
            sheet.pull(400f)

            val closed = sheet.release(
                velocityY = 0f,
                heightPx = 1000f,
                openSpec = sheetOpenSpringSpec(),
                closeSpec = sheetCloseSpringSpec(),
            )

            assertTrue(closed)
            assertEquals(0f, sheet.progress.value, 1e-4f)
            assertEquals(0f, sheet.pulled, 1e-4f)
        }
    }

    @Test
    fun releaseClosesPastVelocityThreshold() = runBlocking {
        withContext(SteppingFrameClock()) {
            val sheet = PlayerSheetState()
            sheet.progress.snapTo(1f)
            sheet.pull(50f)

            val closed = sheet.release(
                velocityY = 1_400f,
                heightPx = 1000f,
                openSpec = sheetOpenSpringSpec(),
                closeSpec = sheetCloseSpringSpec(),
            )

            assertTrue(closed)
            assertEquals(0f, sheet.progress.value, 1e-4f)
        }
    }

    @Test
    fun releaseStaysOpenBelowThresholds() = runBlocking {
        withContext(SteppingFrameClock()) {
            val sheet = PlayerSheetState()
            sheet.progress.snapTo(1f)
            sheet.pull(200f)

            val closed = sheet.release(
                velocityY = 200f,
                heightPx = 1000f,
                openSpec = sheetOpenSpringSpec(),
                closeSpec = sheetCloseSpringSpec(),
            )

            assertFalse(closed)
            assertEquals(1f, sheet.progress.value, 1e-4f)
            assertEquals(0f, sheet.pulled, 1e-4f)
        }
    }

    @Test
    fun openAndCloseSpringsNeverOvershootAndCloseIsQuicker() {
        val openVec = sheetOpenSpringSpec<Float>().vectorize(Float.VectorConverter)
        val closeVec = sheetCloseSpringSpec<Float>().vectorize(Float.VectorConverter)

        val openDurNs = openVec.getDurationNanos(AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f))
        val closeDurNs = closeVec.getDurationNanos(AnimationVector1D(1f), AnimationVector1D(0f), AnimationVector1D(0f))

        val openMs = openDurNs / 1_000_000L
        val closeMs = closeDurNs / 1_000_000L
        assertTrue(openMs in 320L..520L, "Expected open settle in 320..520ms, was ${openMs}ms")
        assertTrue(closeMs < openMs, "Expected close (${closeMs}ms) < open (${openMs}ms)")

        val stepNs = 4_000_000L
        var t = 0L
        while (t <= openDurNs) {
            val v = openVec.getValueFromNanos(t, AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)).value
            assertTrue(v <= 1f + 1e-4f && v >= -1e-4f, "Open spring out of [0,1]: $v at ${t / 1_000_000L}ms")
            t += stepNs
        }
        t = 0L
        while (t <= closeDurNs) {
            val v = closeVec.getValueFromNanos(t, AnimationVector1D(1f), AnimationVector1D(0f), AnimationVector1D(0f)).value
            assertTrue(v <= 1f + 1e-4f && v >= -1e-4f, "Close spring out of [0,1]: $v at ${t / 1_000_000L}ms")
            t += stepNs
        }
    }
}
