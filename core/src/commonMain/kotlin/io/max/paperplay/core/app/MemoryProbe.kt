package io.max.paperplay.core.app

import io.max.paperplay.player.PlayLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Writes one line about memory every minute, to the shared log (tag "memory"), so a leak shows as a number that only
 * climbs. The entry points say how to measure on their platform by setting [sample]; the app adds its own counters.
 */
object MemoryProbe {
    /** Heap, native memory, threads: what this platform can tell. Null where nothing is set, and then nothing is logged. */
    @Volatile var sample: (() -> String)? = null

    /** Also called with each line when set; the desktop app prints it to the console. */
    @Volatile var echo: ((String) -> Unit)? = null

    private var started = false

    fun start(scope: CoroutineScope, intervalMs: Long = 60_000, extra: () -> String = { "" }) {
        if (started) return
        started = true
        scope.launch(Dispatchers.Default) {
            while (true) {
                val s = sample
                if (s != null) {
                    val line = runCatching { s() + extra().let { if (it.isEmpty()) "" else " | $it" } }.getOrElse { "could not measure: ${it.message}" }
                    PlayLog.log("memory", line)
                    echo?.invoke(line)
                }
                delay(intervalMs)
            }
        }
    }
}
