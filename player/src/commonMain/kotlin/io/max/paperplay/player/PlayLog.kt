package io.max.paperplay.player

import kotlin.concurrent.Volatile

/**
 * A running record of what the app does: player state, stream opens and ends, decoder, errors, queue moves, screens,
 * settings and system events. Anything can write to it from any thread; the app sets [sink] to write the lines to a file.
 * Lines written before the sink exists (the first moments of start-up) are held, at most [EARLY_MAX], and handed over when it is set.
 */
object PlayLog {
    private const val EARLY_MAX = 300
    private val early = ArrayList<Triple<Long, String, String>>()

    @Volatile private var target: ((at: Long, tag: String, message: String) -> Unit)? = null

    var sink: ((at: Long, tag: String, message: String) -> Unit)?
        get() = target
        set(value) {
            // Drained under the lock before the target is published, so a new line cannot overtake a held one.
            synchronized(early) {
                if (value != null) { early.forEach { (at, tag, m) -> value(at, tag, m) }; early.clear() }
                target = value
            }
        }

    /** Wall-clock milliseconds; set by the app, since common code has no clock. */
    @Volatile var nowMs: () -> Long = { 0L }

    fun log(tag: String, message: String) {
        val t = target
        if (t != null) { t(nowMs(), tag, message); return }
        synchronized(early) {
            val again = target
            if (again != null) again(nowMs(), tag, message)
            else if (early.size < EARLY_MAX) early.add(Triple(nowMs(), tag, message))
        }
    }
}
