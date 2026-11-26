package io.max.paperplay.ui.app

import io.max.paperplay.ui.theme.AmbientClock
import io.max.paperplay.ui.theme.DesktopFrameMonitor
import io.max.paperplay.ui.theme.Motion
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMotionTest {
    private fun simulateWheelTravel(hz: Int, durationMs: Int, impulsePx: Float = 252f): Pair<Float, Boolean> {
        val physics = SmoothWheelScrollPhysics()
        physics.addImpulse(impulsePx, maxAccumulatedPx = 3000f)
        val dt = 1f / hz
        val steps = ((durationMs / 1000f) * hz).toInt()
        var moved = 0f
        repeat(steps) {
            moved += physics.step(dt, reduceMotion = false)
        }
        return moved to physics.hasPending
    }

    @Test
    fun smoothWheelScrollPhysicsIsRefreshRateInvariantAcross60_120_144_240Hz() {
        val rates = listOf(60, 120, 144, 240)
        val at100ms = rates.map { simulateWheelTravel(it, 100).first }
        val at250ms = rates.map { simulateWheelTravel(it, 250).first }
        val settled = rates.map { simulateWheelTravel(it, 1500) }

        val min100 = at100ms.min()
        val max100 = at100ms.max()
        assertTrue((max100 - min100) / max100 < 0.04f, "100ms travel diverged across Hz: $at100ms")

        val min250 = at250ms.min()
        val max250 = at250ms.max()
        assertTrue((max250 - min250) / max250 < 0.02f, "250ms travel diverged across Hz: $at250ms")

        for ((dist, pending) in settled) {
            assertFalse(pending, "Wheel physics did not settle after 1.5s")
            assertTrue(abs(dist - 252f) < 0.01f, "Settled distance $dist != 252f")
        }
    }

    @Test
    fun smoothWheelScrollReversesInstantlyAndHonorsReduceMotion() {
        val physics = SmoothWheelScrollPhysics()
        physics.addImpulse(400f, 1200f)
        assertTrue(physics.hasPending)
        physics.addImpulse(-120f, 1200f)
        assertEquals(-120f, physics.remainingPx, "Reversing scroll must discard opposite momentum")

        val step = physics.step(1f / 60f, reduceMotion = true)
        assertEquals(-120f, step)
        assertFalse(physics.hasPending)
    }

    @Test
    fun fadingScrollbarControllerFadesAfterIdleAndStaysVisibleOnHoverOrDrag() {
        val c = FadingScrollbarController()
        assertEquals(0f, c.targetAlpha)
        assertFalse(c.isVisible)

        c.onScrollActivity()
        assertTrue(c.isVisible)
        assertEquals(0.55f, c.targetAlpha)

        c.onHoverChanged(true)
        assertEquals(0.90f, c.targetAlpha)

        c.onIdleTimeout()
        assertTrue(c.isVisible, "Hover must keep scrollbar visible on idle timeout")
        assertEquals(0.90f, c.targetAlpha)

        c.onHoverChanged(false)
        c.onIdleTimeout()
        assertFalse(c.isVisible)
        assertEquals(0f, c.targetAlpha)
    }

    @Test
    fun desktopNowPlayingStateMachineTransitionsAndQueueDrag() {
        val np = DesktopNowPlayingState()
        assertEquals(NowPlayingPane.Queue, np.pane)
        assertFalse(np.fullLyrics)

        np.selectPane(NowPlayingPane.Lyrics)
        assertEquals(NowPlayingPane.Lyrics, np.pane)

        np.setFullLyricsMode(true)
        assertTrue(np.fullLyrics)

        val moves = mutableListOf<Pair<Int, Int>>()
        np.startDrag(1)
        np.updateDrag(deltaPx = 40f, itemHeightPx = 50f, queueSize = 5) { f, t -> moves += f to t }
        assertEquals(listOf(1 to 2), moves)
        assertEquals(2, np.draggingIndex)

        np.updateDrag(deltaPx = -50f, itemHeightPx = 50f, queueSize = 5) { f, t -> moves += f to t }
        assertEquals(listOf(1 to 2, 2 to 1), moves)
        np.endDrag()
        assertEquals(null, np.draggingIndex)
    }

    @Test
    fun desktopFrameMonitorComputesPercentilesAndVsyncBudget() {
        val m = DesktopFrameMonitor(capacity = 128)
        var t = 1_000_000_000L
        m.recordFrameNanos(t)
        // 100 frames at 120 Hz (8.33 ms) + 2 spikes at 20 ms (> 2 * 8.33 ms = 16.67 ms)
        repeat(100) {
            t += 8_333_333L
            m.recordFrameNanos(t)
        }
        t += 20_000_000L
        m.recordFrameNanos(t)
        t += 20_000_000L
        m.recordFrameNanos(t)

        val stats = m.snapshot()
        assertEquals(102, stats.sampleCount)
        assertEquals(120, stats.estimatedHz)
        assertEquals(2, stats.framesOver2Vsync)
        assertTrue(stats.maxMs >= 19.9f)
    }

    @Test
    fun zeroPerFrameHeapAllocationsInHotMotionLoops() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true

        val tid = Thread.currentThread().id
        val wheel = SmoothWheelScrollPhysics()
        val clock = AmbientClock()
        val scrollbar = FadingScrollbarController()
        val monitor = DesktopFrameMonitor(capacity = 512)

        // Warm up JIT
        repeat(5_000) {
            wheel.addImpulse(50f, 1000f)
            wheel.step(1f / 120f)
            clock.advance(1f / 30f)
            scrollbar.onScrollActivity()
            scrollbar.onIdleTimeout()
            monitor.recordFrameNanos((it + 1) * 8_333_333L)
        }

        wheel.stop()
        wheel.addImpulse(5000f, 5000f)
        var ns = 10_000_000_000L
        val before = bean.getThreadAllocatedBytes(tid)
        var sink = 0f
        repeat(10_000) { i ->
            if (i % 60 == 0) wheel.addImpulse(200f, 5000f)
            sink += wheel.step(1f / 240f)
            clock.advance(1f / 240f)
            sink += scrollbar.targetAlpha
            ns += 4_166_667L
            monitor.recordFrameNanos(ns)
        }
        val allocated = bean.getThreadAllocatedBytes(tid) - before
        assertTrue(sink.isFinite())
        assertTrue(allocated <= 2048L, "Expected near-zero (< 0.2 bytes/frame) allocation across 10,000 hot motion frames, got $allocated")
    }

    @Test
    fun idleLoopUsesZeroUpdatesWhenSettled() {
        val wheel = SmoothWheelScrollPhysics()
        assertFalse(wheel.hasPending)
        assertEquals(0f, wheel.step(1f / 60f))
        assertTrue(Motion.Desktop.HoverMs < Motion.Desktop.PageEnterMs)
    }
}
