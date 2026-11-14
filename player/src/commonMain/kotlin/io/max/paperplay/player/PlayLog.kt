package io.max.paperplay.player

import kotlin.concurrent.Volatile

/**
 * A running record of what playback does: player state, stream opens and ends, decoder, errors, queue moves.
 * Anything can write to it from any thread; the app sets [sink] to write the lines to a file.
 */
object PlayLog {
    @Volatile var sink: ((at: Long, tag: String, message: String) -> Unit)? = null

    /** Wall-clock milliseconds; set by the app, since common code has no clock. */
    @Volatile var nowMs: () -> Long = { 0L }

    fun log(tag: String, message: String) {
        sink?.invoke(nowMs(), tag, message)
    }
}
