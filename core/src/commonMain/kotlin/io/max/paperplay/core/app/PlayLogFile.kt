package io.max.paperplay.core.app

import io.max.paperplay.core.backup.Fs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The playback log on disk: every line playback wrote, kept across restarts. Lines are queued and written in
 * batches off the playing thread. At [MAX_BYTES] the file moves to a second file and a new one starts, so at most
 * two files exist and the newest lines are never dropped.
 */
class PlayLogFile(dataDir: String, scope: CoroutineScope, private val stamp: (Long) -> String) {
    private val dir = dataDir.trimEnd('/', '\\')
    val path = "$dir/playback-log.txt"
    private val older = "$dir/playback-log-older.txt"
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val token = Regex("\\b\\d{5,}:[A-Za-z0-9_-]{20,}\\b")

    init {
        scope.launch(Dispatchers.Default) {
            for (first in queue) {
                val sb = StringBuilder(first)
                while (true) sb.append(queue.tryReceive().getOrNull() ?: break)
                runCatching {
                    Fs.append(path, sb.toString())
                    if (Fs.size(path) > MAX_BYTES) {
                        Fs.deleteRecursively(older)
                        Fs.rename(path, older)
                    }
                }
            }
        }
    }

    fun add(at: Long, tag: String, message: String) {
        queue.trySend("${stamp(at)}  $tag  ${message.replace(token, "<bot token>")}\n")
    }

    /** The whole log, oldest first. */
    fun readAll(): String {
        val system = (Fs.readText("$dir/logcat-older.txt") + Fs.readText("$dir/logcat.txt")).let { if (it.length > MAX_SYSTEM_CHARS) it.takeLast(MAX_SYSTEM_CHARS) else it }
        return Fs.readText(older) + Fs.readText(path) +
            if (system.isEmpty()) "" else "\n\n===== Android system log for this app (logcat, every tag and level; the newest ${system.length / 1024} KB) =====\n" + system
    }

    fun clear() {
        Fs.deleteRecursively(older)
        Fs.deleteRecursively(path)
    }

    private companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_SYSTEM_CHARS = 6_000_000
    }
}
