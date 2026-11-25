package io.max.paperplay.desktop

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.max.paperplay.ui.AppForegroundState
import io.max.paperplay.ui.app.DesktopMotionBenchUi
import io.max.paperplay.ui.app.createDesktopBenchSongs
import io.max.paperplay.ui.theme.DesktopFrameMonitor
import io.max.paperplay.ui.theme.DesktopFrameSampler
import io.max.paperplay.ui.theme.DesktopFrameStats
import kotlinx.coroutines.delay
import java.lang.management.ManagementFactory

/**
 * Deterministic, scripted Compose Desktop Skia motion benchmark.
 * Runs against in-memory stubbed data (never touches user library or DB).
 * Invoked via `-Dpaperplay.motion.bench=true` or `--motion-bench`.
 */
internal object DesktopMotionBench {
    private data class FlowResult(val name: String, val stats: DesktopFrameStats)

    fun run() {
        val idleSeconds = System.getProperty("paperplay.motion.bench.idleSec")?.toIntOrNull()?.coerceIn(1, 120) ?: 5
        val songs = createDesktopBenchSongs(500)

        application {
            val winState = rememberWindowState(width = 1280.dp, height = 800.dp)
            var showNow by remember { mutableStateOf(false) }
            var pageName by remember { mutableStateOf("home") }
            var navDir by remember { mutableIntStateOf(0) }
            var sidebarExpanded by remember { mutableStateOf(true) }
            var showPanel by remember { mutableStateOf(true) }
            var trackIndex by remember { mutableIntStateOf(0) }
            var showMini by remember { mutableStateOf(false) }
            val listState = rememberLazyListState()
            val monitor = remember { DesktopFrameMonitor(capacity = 8192) }
            var sampling by remember { mutableStateOf(true) }
            var showSettings by remember { mutableStateOf(false) }

            Window(
                onCloseRequest = ::exitApplication,
                title = "PaperPlay Desktop Motion Bench",
                state = winState,
            ) {
                DesktopFrameSampler(monitor, sampling)

                LaunchedEffect(Unit) {
                    WindowsTitleBar.applySurfaceColor(window, 0xFF111318.toInt())
                    val results = mutableListOf<FlowResult>()
                    val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                    val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

                    suspend fun measure(name: String, block: suspend () -> Unit) {
                        monitor.reset()
                        sampling = true
                        block()
                        delay(120)
                        results += FlowResult(name, monitor.snapshot())
                    }

                    // Warm-up
                    delay(400)

                    measure("1_open_close_now_playing") {
                        repeat(3) {
                            navDir = 1; showNow = true; delay(280)
                            navDir = -1; showNow = false; delay(280)
                        }
                    }
                    measure("2_switch_sidebar_pages") {
                        for (p in listOf("songs", "albums", "artists", "home")) {
                            navDir = 0; pageName = p; delay(240)
                        }
                    }
                    measure("3_open_close_settings") {
                        repeat(3) {
                            navDir = 1; showSettings = true; delay(240)
                            navDir = -1; showSettings = false; delay(240)
                        }
                    }
                    measure("4_wheel_scroll_500_songs") {
                        pageName = "songs"; delay(120)
                        for (idx in listOf(40, 120, 260, 420, 0)) {
                            listState.animateScrollToItem(idx)
                            delay(80)
                        }
                    }
                    measure("5_continuous_window_resize") {
                        for (w in listOf(1100, 960, 860, 1020, 1280)) {
                            winState.size = DpSize(w.dp, 760.dp)
                            delay(120)
                        }
                        winState.size = DpSize(1280.dp, 800.dp)
                        delay(150)
                    }
                    measure("6_sidebar_collapse_expand_interrupt") {
                        sidebarExpanded = false; delay(90)
                        sidebarExpanded = true; delay(90)
                        sidebarExpanded = false; delay(260)
                        sidebarExpanded = true; delay(260)
                    }
                    measure("7_open_close_mini_window") {
                        repeat(3) {
                            showMini = true; delay(260)
                            showMini = false; delay(220)
                        }
                    }
                    measure("8_track_change") {
                        repeat(4) { i ->
                            trackIndex = (i + 1) % 10
                            delay(240)
                        }
                    }

                    // Idle CPU still (no animations running)
                    sampling = false
                    delay(300)
                    val cpuStartStill = osBean.processCpuTime
                    val wallStartStill = System.nanoTime()
                    delay(idleSeconds * 1000L)
                    val cpuUsedStill = (osBean.processCpuTime - cpuStartStill).coerceAtLeast(0L)
                    val wallStill = (System.nanoTime() - wallStartStill).coerceAtLeast(1L)
                    val idleStillPct = (cpuUsedStill.toDouble() / (wallStill * cpus)) * 100.0

                    // Idle CPU hidden/minimized
                    AppForegroundState.isVisible = false
                    AppForegroundState.isForeground = false
                    delay(200)
                    val cpuStartHidden = osBean.processCpuTime
                    val wallStartHidden = System.nanoTime()
                    delay(idleSeconds * 1000L)
                    val cpuUsedHidden = (osBean.processCpuTime - cpuStartHidden).coerceAtLeast(0L)
                    val wallHidden = (System.nanoTime() - wallStartHidden).coerceAtLeast(1L)
                    val idleHiddenPct = (cpuUsedHidden.toDouble() / (wallHidden * cpus)) * 100.0

                    println("=== DESKTOP MOTION BENCHMARK ===")
                    println("OS=${System.getProperty("os.name")} Java=${System.getProperty("java.version")} SkikoAPI=${System.getProperty("skiko.renderApi") ?: "auto"}")
                    for (r in results) {
                        val s = r.stats
                        println(
                            "FLOW ${r.name}: n=${s.sampleCount} hz=${s.estimatedHz} p50=${"%.2f".format(s.p50Ms)}ms " +
                                "p95=${"%.2f".format(s.p95Ms)}ms p99=${"%.2f".format(s.p99Ms)}ms max=${"%.2f".format(s.maxMs)}ms " +
                                ">2vsync=${s.framesOver2Vsync} (${"%.1f".format(s.over2VsyncPct)}%)"
                        )
                    }
                    println("IDLE_STILL_${idleSeconds}S_CPU_PCT=${"%.3f".format(idleStillPct)}%")
                    println("IDLE_HIDDEN_${idleSeconds}S_CPU_PCT=${"%.3f".format(idleHiddenPct)}%")
                    println("=== END DESKTOP MOTION BENCHMARK ===")
                    exitApplication()
                }

                DesktopMotionBenchUi(
                    songs = songs,
                    trackIndex = trackIndex,
                    showNow = showNow,
                    showSettings = showSettings,
                    pageName = pageName,
                    navMoveForward = navDir,
                    sidebarExpanded = sidebarExpanded,
                    showPanel = showPanel,
                    showMini = showMini,
                    listState = listState,
                    onCloseNow = { showNow = false },
                    onOpenNow = { showNow = true },
                )
            }
        }
    }
}
