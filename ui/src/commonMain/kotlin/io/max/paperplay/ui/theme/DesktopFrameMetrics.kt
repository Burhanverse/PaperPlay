package io.max.paperplay.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Aggregated frame-timing statistics for desktop motion validation. */
@Immutable
data class DesktopFrameStats(
    val sampleCount: Int,
    val p50Ms: Float,
    val p90Ms: Float,
    val p95Ms: Float,
    val p99Ms: Float,
    val maxMs: Float,
    val estimatedHz: Int,
    val vsyncPeriodMs: Float,
    val framesOver2Vsync: Int,
    val over2VsyncPct: Float,
)

/**
 * Zero-allocation ring buffer recording frame deltas via [recordFrameNanos].
 */
class DesktopFrameMonitor(capacity: Int = 4096) {
    private val cap = capacity.coerceAtLeast(16)
    private val samplesMs = FloatArray(cap)
    private val scratch = FloatArray(cap)
    private var writeIdx = 0
    private var count = 0
    private var prevFrameNs = 0L

    var revision by mutableIntStateOf(0)
        private set

    fun recordFrameNanos(frameTimeNanos: Long) {
        if (prevFrameNs != 0L && frameTimeNanos > prevFrameNs) {
            val dtMs = (frameTimeNanos - prevFrameNs) / 1_000_000f
            if (dtMs.isFinite() && dtMs > 0f && dtMs < 500f) {
                samplesMs[writeIdx] = dtMs
                writeIdx = (writeIdx + 1) % cap
                if (count < cap) count++
                revision++
            }
        }
        prevFrameNs = frameTimeNanos
    }

    fun reset() {
        writeIdx = 0
        count = 0
        prevFrameNs = 0L
        revision++
    }

    fun snapshot(forcedVsyncMs: Float? = null): DesktopFrameStats {
        val n = count
        if (n == 0) {
            val vsync = forcedVsyncMs ?: (1000f / 60f)
            return DesktopFrameStats(0, 0f, 0f, 0f, 0f, 0f, (1000f / vsync).roundToInt(), vsync, 0, 0f)
        }
        if (n < cap) {
            samplesMs.copyInto(scratch, 0, 0, n)
        } else {
            val tail = cap - writeIdx
            samplesMs.copyInto(scratch, 0, writeIdx, cap)
            samplesMs.copyInto(scratch, tail, 0, writeIdx)
        }
        scratch.sort(0, n)
        fun pct(p: Float): Float {
            val idx = ((n - 1) * p).toInt().coerceIn(0, n - 1)
            return scratch[idx]
        }
        val p50 = pct(0.50f)
        val p90 = pct(0.90f)
        val p95 = pct(0.95f)
        val p99 = pct(0.99f)
        val max = scratch[n - 1]
        val inferredHz = snapRefreshHz(if (p50 > 0.1f) 1000f / p50 else 60f)
        val vsyncMs = forcedVsyncMs ?: (1000f / inferredHz)
        val budgetMs = vsyncMs * 2f
        var over = 0
        for (i in 0 until n) {
            if (scratch[i] > budgetMs) over++
        }
        return DesktopFrameStats(
            sampleCount = n,
            p50Ms = p50,
            p90Ms = p90,
            p95Ms = p95,
            p99Ms = p99,
            maxMs = max,
            estimatedHz = (1000f / vsyncMs).roundToInt(),
            vsyncPeriodMs = vsyncMs,
            framesOver2Vsync = over,
            over2VsyncPct = (over * 100f) / n,
        )
    }

    private fun snapRefreshHz(rawHz: Float): Int = when {
        rawHz >= 200f -> 240
        rawHz >= 132f -> 144
        rawHz >= 105f -> 120
        rawHz >= 75f -> 90
        else -> 60
    }
}

/**
 * Samples frame timestamps into [monitor] ONLY while [enabled] is true.
 * When disabled, zero frame callbacks run.
 */
@Composable
fun DesktopFrameSampler(monitor: DesktopFrameMonitor, enabled: Boolean) {
    LaunchedEffect(monitor, enabled) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { now -> monitor.recordFrameNanos(now) }
        }
    }
}

/** Compact corner overlay displaying live frame timing stats while [enabled] is true. */
@Composable
fun DesktopFrameOverlay(
    monitor: DesktopFrameMonitor,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    DesktopFrameSampler(monitor, enabled)
    if (!enabled) return
    val rev = monitor.revision
    val stats = monitor.snapshot()
    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
    ) {
        Text(
            text = "~${stats.estimatedHz}Hz | n=${stats.sampleCount} (r=$rev) | p50=${stats.p50Ms.roundToInt()}ms p95=${stats.p95Ms.roundToInt()}ms max=${stats.maxMs.roundToInt()}ms | >2v=${stats.framesOver2Vsync}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
