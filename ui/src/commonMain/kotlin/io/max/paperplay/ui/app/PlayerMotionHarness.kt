package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.max.paperplay.player.PlayLog
import kotlinx.coroutines.delay

/** Aggregated frame-timing and progress-invariant report for automated player motion benchmarks. */
internal data class MotionBenchReport(
    val cycles: Int,
    val totalFrames: Int,
    val minProgress: Float,
    val maxProgress: Float,
    val p95FrameMs: Float,
    val maxFrameMs: Float,
    val framesOver16ms: Int,
    val framesOver20ms: Int,
    val anomalies: List<String>,
)

internal class MotionStatsAccumulator(private val cycles: Int) {
    private val frameDurationsMs = ArrayList<Float>(cycles * 60)
    private val anomalies = ArrayList<String>()
    private var minProgress = Float.POSITIVE_INFINITY
    private var maxProgress = Float.NEGATIVE_INFINITY

    fun recordFrame(dtMs: Float, progress: Float, visualProgress: Float) {
        if (dtMs.isFinite() && dtMs >= 0f) frameDurationsMs.add(dtMs)
        if (progress < minProgress) minProgress = progress
        if (progress > maxProgress) maxProgress = progress
        if (progress < -1e-4f || progress > 1f + 1e-4f) {
            anomalies.add("progress_out_of_bounds=$progress")
        }
        if (visualProgress < -1e-4f) {
            anomalies.add("visual_progress_negative=$visualProgress")
        }
    }

    fun recordAnomaly(message: String) {
        anomalies.add(message)
    }

    fun finish(): MotionBenchReport {
        val sorted = frameDurationsMs.sorted()
        val p95 = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * 0.95f).toInt()]
        val max = sorted.lastOrNull() ?: 0f
        return MotionBenchReport(
            cycles = cycles,
            totalFrames = frameDurationsMs.size,
            minProgress = if (minProgress.isFinite()) minProgress else 0f,
            maxProgress = if (maxProgress.isFinite()) maxProgress else 0f,
            p95FrameMs = p95,
            maxFrameMs = max,
            framesOver16ms = frameDurationsMs.count { it > 16.67f },
            framesOver20ms = frameDurationsMs.count { it > 20f },
            anomalies = anomalies.distinct(),
        )
    }
}

/**
 * Dev-gated motion test harness that opens and closes [controller] [cycles] times while sampling
 * frame intervals and progress invariants via `withFrameNanos`, logging a summary to `PlayLog`.
 */
@Composable
internal fun PlayerMotionHarness(
    active: Boolean,
    controller: PlayerTransitionController,
    cycles: Int = 20,
    onDone: (MotionBenchReport) -> Unit,
    modifier: Modifier = Modifier,
) {
    var statusText by remember { mutableStateOf("Preparing motion test...") }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val acc = MotionStatsAccumulator(cycles)
        controller.snapClosed()
        withFrameNanos { }

        for (cycle in 1..cycles) {
            statusText = "Motion test $cycle / $cycles"
            // Open phase
            controller.open()
            var lastNs = 0L
            var guard = 0
            while (controller.phase != PlayerSheetPhase.Open && guard++ < 180) {
                withFrameNanos { now ->
                    if (lastNs != 0L) {
                        acc.recordFrame((now - lastNs) / 1_000_000f, controller.progress, controller.visualProgress)
                    }
                    lastNs = now
                }
            }
            if (controller.phase != PlayerSheetPhase.Open) {
                acc.recordAnomaly("cycle_${cycle}_open_did_not_settle:${controller.phase}")
            }
            delay(80)

            // Close phase
            controller.close()
            lastNs = 0L
            guard = 0
            while (controller.phase != PlayerSheetPhase.Closed && guard++ < 180) {
                withFrameNanos { now ->
                    if (lastNs != 0L) {
                        acc.recordFrame((now - lastNs) / 1_000_000f, controller.progress, controller.visualProgress)
                    }
                    lastNs = now
                }
            }
            if (controller.phase != PlayerSheetPhase.Closed) {
                acc.recordAnomaly("cycle_${cycle}_close_did_not_settle:${controller.phase}")
            }
            delay(80)
        }

        val report = acc.finish()
        PlayLog.log(
            "motion_bench",
            "cycles=${report.cycles} frames=${report.totalFrames} minP=${report.minProgress} maxP=${report.maxProgress} " +
                "p95=${report.p95FrameMs}ms max=${report.maxFrameMs}ms >16ms=${report.framesOver16ms} >20ms=${report.framesOver20ms} " +
                "anomalies=${report.anomalies.joinToString(";").ifEmpty { "none" }}",
        )
        onDone(report)
    }

    if (active) {
        Box(
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(statusText, style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}
