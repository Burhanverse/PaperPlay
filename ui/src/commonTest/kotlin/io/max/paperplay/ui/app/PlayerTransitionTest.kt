package io.max.paperplay.ui.app

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import io.max.paperplay.ui.theme.PlayerMotionTokens
import io.max.paperplay.ui.theme.playerCloseSpringSpec
import io.max.paperplay.ui.theme.playerOpenSpringSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class TestFrameClock(private val stepNs: Long = 16_000_000L) : MonotonicFrameClock {
    private var nowNs = 0L
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        nowNs += stepNs
        return onFrame(nowNs)
    }
}

private fun runWithTestController(
    reduceMotion: Boolean = false,
    initiallyOpen: Boolean = false,
    block: suspend (PlayerTransitionController, CoroutineScope) -> Unit,
) = runBlocking(TestFrameClock()) {
    val scope = CoroutineScope(coroutineContext + Job())
    val controller = PlayerTransitionController(
        scope = scope,
        reduceMotion = reduceMotion,
        standalone = false,
        initiallyOpen = initiallyOpen,
    )
    try {
        block(controller, scope)
    } finally {
        controller.dispose()
    }
}

class PlayerTransitionTest {
    @Test
    fun openAndCloseSpringsNeverOvershootAndMeetDurationTargets() {
        val openVec = playerOpenSpringSpec().vectorize(Float.VectorConverter)
        val closeVec = playerCloseSpringSpec().vectorize(Float.VectorConverter)

        val openDurNs = openVec.getDurationNanos(AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f))
        val closeDurNs = closeVec.getDurationNanos(AnimationVector1D(1f), AnimationVector1D(0f), AnimationVector1D(0f))

        val openMs = openDurNs / 1_000_000L
        val closeMs = closeDurNs / 1_000_000L
        assertTrue(openMs in 350L..450L, "Expected open settle in 350..450ms, was ${openMs}ms")
        assertTrue(closeMs < openMs, "Expected close (${closeMs}ms) < open (${openMs}ms)")

        val stepNs = 4_000_000L
        var t = 0L
        while (t <= openDurNs) {
            val v = openVec.getValueFromNanos(t, AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)).value
            assertTrue(v in -1e-4f..(1f + 1e-4f), "Open spring out of [0,1]: $v at ${t / 1_000_000L}ms")
            t += stepNs
        }
        t = 0L
        while (t <= closeDurNs) {
            val v = closeVec.getValueFromNanos(t, AnimationVector1D(1f), AnimationVector1D(0f), AnimationVector1D(0f)).value
            assertTrue(v in -1e-4f..(1f + 1e-4f), "Close spring out of [0,1]: $v at ${t / 1_000_000L}ms")
            t += stepNs
        }
    }

    @Test
    fun openAndCloseReachSettledPhases() = runWithTestController { c, _ ->
        assertEquals(PlayerSheetPhase.Closed, c.phase)
        assertFalse(c.isComposed)
        assertTrue(c.isSettled)

        c.open()
        assertEquals(PlayerSheetPhase.Opening, c.phase)
        assertTrue(c.isComposed)
        assertFalse(c.isSettled)

        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Open, c.phase)
        assertEquals(1f, c.progress, 1e-4f)
        assertTrue(c.isSettled)
        assertTrue(c.isCovered)
        assertTrue(c.isPlayerSystemBars)

        c.close()
        assertEquals(PlayerSheetPhase.Closing, c.phase)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Closed, c.phase)
        assertEquals(0f, c.progress, 1e-4f)
        assertFalse(c.isComposed)
        assertFalse(c.isCovered)
        assertFalse(c.isPlayerSystemBars)
    }

    @Test
    fun interruptOpenWithCloseAndCloseWithOpenContinuously() = runWithTestController { c, _ ->
        c.snapOpen()
        c.dragBy(dyPx = 200f, heightPx = 1000f)
        // Interrupted into dragging at 0.8
        assertEquals(PlayerSheetPhase.Dragging, c.phase)
        c.release(velocityYPxPerSec = 0f, heightPx = 1000f)
        // 0.2 pulled < 0.35 threshold -> Settling back to Open, interrupt with close()
        assertEquals(PlayerSheetPhase.Settling, c.phase)
        val mid = c.progress
        c.close()
        assertEquals(PlayerSheetPhase.Closing, c.phase)
        assertEquals(mid, c.progress, 1e-4f)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Closed, c.phase)

        // Start opening, interrupt immediately with close, then re-open
        c.open()
        c.close()
        c.open()
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Open, c.phase)
        assertEquals(1f, c.progress, 1e-4f)
    }

    @Test
    fun dragDownIsOneToOneAndUpwardPullRubberBandsWithinBounds() = runWithTestController(initiallyOpen = true) { c, _ ->
        c.dragBy(dyPx = -500f, heightPx = 1000f)
        assertEquals(PlayerSheetPhase.Dragging, c.phase)
        assertEquals(1f, c.progress, 1e-4f)
        assertTrue(c.visualProgress > 1f)
        assertTrue(c.visualProgress <= 1f + PlayerMotionTokens.RUBBER_BAND_MAX)

        // Pulling back down unwinds rubber band first, then tracks 1:1 below 1f
        c.dragBy(dyPx = 700f, heightPx = 1000f)
        assertEquals(0.8f, c.progress, 1e-4f)
        assertEquals(0.8f, c.visualProgress, 1e-4f)

        // Extreme downward drag clamps at 0f
        c.dragBy(dyPx = 2000f, heightPx = 1000f)
        assertEquals(0f, c.progress, 1e-4f)
    }

    @Test
    fun releaseDecidesByDistanceAndVelocityThresholds() = runWithTestController(initiallyOpen = true) { c, _ ->
        // Distance > 0.35 closes
        c.dragBy(dyPx = 400f, heightPx = 1000f)
        c.release(velocityYPxPerSec = 0f, heightPx = 1000f)
        assertEquals(PlayerSheetPhase.Closing, c.phase)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Closed, c.phase)

        // Fast downward flick > 1200 px/s closes even at small distance
        c.snapOpen()
        c.dragBy(dyPx = 50f, heightPx = 1000f)
        c.release(velocityYPxPerSec = 1400f, heightPx = 1000f)
        assertEquals(PlayerSheetPhase.Closing, c.phase)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Closed, c.phase)

        // Slow drag < 0.35 stays open
        c.snapOpen()
        c.dragBy(dyPx = 200f, heightPx = 1000f)
        c.release(velocityYPxPerSec = 200f, heightPx = 1000f)
        assertEquals(PlayerSheetPhase.Settling, c.phase)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Open, c.phase)

        // Fast upward flick stays open even past 0.35 distance
        c.dragBy(dyPx = 500f, heightPx = 1000f)
        c.release(velocityYPxPerSec = -1400f, heightPx = 1000f)
        assertEquals(PlayerSheetPhase.Settling, c.phase)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Open, c.phase)
    }

    @Test
    fun predictiveBackProgressCommitAndCancel() = runWithTestController(initiallyOpen = true) { c, _ ->
        c.backProgress(0.5f)
        assertEquals(PlayerSheetPhase.BackGesture, c.phase)
        assertEquals(1f - 0.5f * PlayerMotionTokens.BACK_GESTURE_MAX_PULL, c.progress, 1e-4f)
        assertFalse(c.isCovered)

        c.cancelBack()
        assertEquals(PlayerSheetPhase.Settling, c.phase)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Open, c.phase)
        assertEquals(1f, c.progress, 1e-4f)

        c.backProgress(0.8f)
        c.commitBack()
        assertEquals(PlayerSheetPhase.Closing, c.phase)
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Closed, c.phase)
        assertEquals(0f, c.progress, 1e-4f)
    }

    @Test
    fun derivedFlagsHysteresisAndContentStaging() = runWithTestController { c, _ ->
        // Covered hysteresis
        var covered = false
        covered = nextCovered(covered, PlayerSheetPhase.Opening, 0.985f)
        assertFalse(covered)
        covered = nextCovered(covered, PlayerSheetPhase.Opening, 0.999f)
        assertTrue(covered)
        covered = nextCovered(covered, PlayerSheetPhase.Open, 0.985f)
        assertTrue(covered)
        covered = nextCovered(covered, PlayerSheetPhase.BackGesture, 0.999f)
        assertFalse(covered)
        covered = nextCovered(true, PlayerSheetPhase.Dragging, 0.999f)
        assertFalse(covered)

        // System bar tint hysteresis
        var sys = false
        sys = nextPlayerSystemBars(sys, PlayerSheetPhase.Opening, 0.50f)
        assertFalse(sys)
        sys = nextPlayerSystemBars(sys, PlayerSheetPhase.Opening, 0.56f)
        assertTrue(sys)
        sys = nextPlayerSystemBars(sys, PlayerSheetPhase.Dragging, 0.46f)
        assertTrue(sys)
        sys = nextPlayerSystemBars(sys, PlayerSheetPhase.Closing, 0.44f)
        assertFalse(sys)

        // Content staging lifecycle
        assertEquals(PlayerContentStage.Chrome, c.contentStage)
        c.snapOpen()
        assertEquals(PlayerContentStage.Interactive, c.contentStage)
        c.setSettledEffectsReady(true)
        assertEquals(PlayerContentStage.SettledEffects, c.contentStage)
        c.backProgress(0.1f)
        assertEquals(PlayerContentStage.Interactive, c.contentStage)
        c.snapClosed()
        assertEquals(PlayerContentStage.Chrome, c.contentStage)
    }

    @Test
    fun reduceMotionUsesFadeAndNeverSlides() = runWithTestController(reduceMotion = true) { c, _ ->
        assertEquals(0f, sheetTranslationY(0.5f, 1000f, reduceMotion = true))
        assertEquals(0.5f, sheetAlpha(0.5f, reduceMotion = true))
        assertEquals(1f, backdropScale(0.5f, reduceMotion = true))
        assertFalse(sheetClipsTopCorners(0.5f, reduceMotion = true))

        c.open()
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Open, c.phase)
        assertEquals(PlayerContentStage.SettledEffects, c.contentStage)
    }

    @Test
    fun rapidTogglingAndDisposeDuringAnimationAreSafe() = runWithTestController { c, _ ->
        repeat(10) { i ->
            if (i % 2 == 0) c.open() else c.close()
        }
        c.open()
        c.awaitIdle()
        assertEquals(PlayerSheetPhase.Open, c.phase)

        c.close()
        c.dispose()
        c.open()
        // After dispose, no further transitions run
        assertEquals(PlayerSheetPhase.Closing, c.phase)
    }

    @Test
    fun visualCurvesStayWithinValidBounds() {
        for (i in 0..100) {
            val p = i / 100f
            assertTrue(backdropDimAlpha(p) in 0f..PlayerMotionTokens.BACKDROP_MAX_DIM_ALPHA)
            assertTrue(backdropScale(p, false) in PlayerMotionTokens.BACKDROP_MIN_SCALE..1f)
            assertTrue(miniPlayerAlpha(p, false) in 0f..1f)
            assertTrue(navBarAlpha(p, false) in 0f..1f)
            assertTrue(sheetTranslationY(p, 1000f, false) in 0f..1000f)
        }
    }

    @Test
    fun coverBoundsRegistryRespectsQueueAndSizeAndFlightEligibility() = runWithTestController { c, _ ->
        val bounds = CoverBoundsRegistry()
        assertFalse(bounds.canFly)
        bounds.miniRect = Rect(10f, 700f, 54f, 744f)
        bounds.fullRect = Rect(40f, 120f, 340f, 420f)
        bounds.fullSizeState = IntSize(300, 300)
        assertTrue(bounds.canFly)

        // Flight active only while transitioning in (0, 1) without reduceMotion
        assertFalse(c.isCoverFlightActive(bounds))
        c.snapOpen()
        assertFalse(c.isCoverFlightActive(bounds))
        c.backProgress(0.5f)
        assertTrue(c.isCoverFlightActive(bounds))

        bounds.queueOpen = true
        assertFalse(bounds.canFly)
        assertFalse(c.isCoverFlightActive(bounds))
        bounds.queueOpen = false

        c.setReduceMotion(true)
        assertFalse(c.isCoverFlightActive(bounds))
    }

    @Test
    fun motionStatsAggregatorComputesP95AndDetectsAnomalies() {
        val acc = MotionStatsAccumulator(cycles = 2)
        for (i in 1..20) {
            acc.recordFrame(dtMs = i.toFloat(), progress = i / 20f, visualProgress = i / 20f)
        }
        acc.recordFrame(dtMs = 22f, progress = 1.02f, visualProgress = 1.02f)
        val report = acc.finish()
        assertEquals(21, report.totalFrames)
        assertEquals(22f, report.maxFrameMs)
        assertTrue(report.p95FrameMs >= 19f)
        assertTrue(report.framesOver16ms >= 5)
        assertEquals(1, report.framesOver20ms)
        assertTrue(report.anomalies.any { it.startsWith("progress_out_of_bounds=") })
    }
}
