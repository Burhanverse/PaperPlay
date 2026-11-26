package io.max.paperplay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import io.max.paperplay.ui.rememberAppForeground
import kotlinx.coroutines.isActive

/**
 * Shared visibility-, foreground-, and Reduce-Motion-gated ambient clock.
 *
 * Advances [seconds] by wall-clock elapsed nanoseconds (capped at [fps] redraws/sec), so
 * animations run at identical speed on 60 Hz, 90 Hz, and 120 Hz displays and pause completely
 * when off-screen, backgrounded, or when Reduce Motion is enabled.
 *
 * Read [seconds], [phase], or [pingPong] ONLY inside `Canvas`, `drawBehind`, `drawWithCache`,
 * `drawWithContent`, or `graphicsLayer` lambdas — never in composition.
 */
@Stable
class AmbientClock {
    private var _seconds by mutableFloatStateOf(0f)
    private var _subscribers by mutableIntStateOf(0)

    val seconds: Float
        get() = _seconds

    val hasSubscribers: Boolean
        get() = _subscribers > 0

    internal fun addSubscriber() {
        _subscribers++
    }

    internal fun removeSubscriber() {
        _subscribers = (_subscribers - 1).coerceAtLeast(0)
    }

    /** Advances clock by [dtSeconds] (for tests and frame loop). */
    fun advance(dtSeconds: Float) {
        if (!dtSeconds.isFinite() || dtSeconds <= 0f) return
        _seconds = (_seconds + dtSeconds) % 100_000f
    }

    /** Normalized sawtooth phase `0f..1f` for [periodMs]. */
    fun phase(periodMs: Int): Float {
        if (periodMs <= 0) return 0f
        val periodSec = periodMs / 1000f
        return (_seconds % periodSec) / periodSec
    }

    /** Normalized triangle wave `0f..1f..0f` for [periodMs] half-cycle. */
    fun pingPong(periodMs: Int): Float {
        val p = phase(periodMs * 2)
        return if (p < 0.5f) p * 2f else (1f - p) * 2f
    }

    /** Registers this composable as an active consumer of the clock while in composition. */
    @Composable
    fun subscribe(active: Boolean = true) {
        DisposableEffect(this, active) {
            if (active) addSubscriber()
            onDispose { if (active) removeSubscriber() }
        }
    }
}

val LocalAmbientClock = staticCompositionLocalOf<AmbientClock?> { null }

/**
 * Creates and drives an [AmbientClock] that sleeps whenever [active] is false, the app is
 * backgrounded, Reduce Motion is on, or no active subscriber is mounted.
 */
@Composable
fun rememberAmbientClock(
    active: Boolean = true,
    fps: Int = LocalMotionTuning.current.ambientFps,
): AmbientClock {
    val clock = remember { AmbientClock() }
    val foreground = rememberAppForeground()
    val reduceMotion = LocalReduceMotion.current
    val running = active && foreground && !reduceMotion && clock.hasSubscribers
    val minIntervalNs = 1_000_000_000L / fps.coerceIn(1, 120)

    LaunchedEffect(running, minIntervalNs) {
        if (!running) return@LaunchedEffect
        var lastNs = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (lastNs == 0L) {
                    lastNs = now
                } else {
                    val dtNs = now - lastNs
                    if (dtNs >= minIntervalNs) {
                        clock.advance((dtNs / 1_000_000_000f).coerceIn(0f, 0.25f))
                        lastNs = now
                    }
                }
            }
        }
    }
    return clock
}
