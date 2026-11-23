package io.max.paperplay.core.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Copies indexed songs into the main chat (or adds them to the library only) one by one, as an app task: it keeps going when the page
 * that started it is closed, can be paused and stopped from Tasks, and waits a minute divided by [perMinute] between copies so Telegram's
 * limits are not hit. Each song is checked again just before it is copied, so one that arrived meanwhile is not copied twice.
 */
class IndexCopier(private val index: ChatIndexApi, private val scope: CoroutineScope, private val perMinute: () -> Int) {
    data class Progress(val done: Int, val failed: Int, val skipped: Int, val total: Int, val paused: Boolean, val why: String?, val libraryOnly: Boolean)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    /** What the last run did, for a status line after it ended. */
    val lastResult = MutableStateFlow<String?>(null)

    @kotlin.concurrent.Volatile private var paused = false
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /** Starts copying [posts]; false when a run is already going (it has to finish or be stopped first). */
    fun start(posts: List<ChatPost>, libraryOnly: Boolean = false): Boolean {
        if (running || posts.isEmpty()) return false
        paused = false
        lastResult.value = null
        job = scope.launch {
            var p = Progress(0, 0, 0, posts.size, false, null, libraryOnly)
            _progress.value = p
            // A chat that refuses one copy refuses them all: the rest of its songs fail at once instead of asking Telegram each time.
            val blocked = HashSet<Long>()
            try {
                posts.forEachIndexed { i, post ->
                    while (paused) { _progress.value = p.copy(paused = true); delay(300) }
                    if (index.alreadyHave(post)) { p = p.copy(skipped = p.skipped + 1); _progress.value = p; return@forEachIndexed }
                    if (post.chatId in blocked) { p = p.copy(failed = p.failed + 1); _progress.value = p; return@forEachIndexed }
                    runCatching { if (libraryOnly) index.addToLibrary(post) else index.copyToMain(post) }
                        .onSuccess { p = p.copy(done = p.done + 1, paused = false) }
                        .onFailure {
                            if (it.message?.contains("restricts saving content") == true) blocked += post.chatId
                            p = p.copy(failed = p.failed + 1, why = p.why ?: it.message, paused = false)
                        }
                    _progress.value = p
                    if (!libraryOnly && post.chatId !in blocked && i < posts.size - 1) delay(60_000L / perMinute().coerceIn(5, 120))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                io.max.paperplay.player.PlayLog.log("copy", "Copy run stopped after ${p.done + p.skipped + p.failed} of ${p.total}: ${e.message ?: "stopped"}")
                throw e
            } catch (e: Throwable) {
                // Anything that ends the run early is logged and shown, so a run that stops halfway says why.
                io.max.paperplay.player.PlayLog.log("copy", "Copy run ended early after ${p.done + p.skipped + p.failed} of ${p.total}: $e")
                p = p.copy(why = "Stopped early: ${e.message ?: e::class.simpleName}")
            } finally {
                val verb = if (libraryOnly) "Added" else "Copied"
                lastResult.value = "$verb ${p.done} of ${p.total}" + (if (p.skipped > 0) ", ${p.skipped} already there" else "") +
                    (if (p.failed > 0) ", ${p.failed} failed. ${p.why.orEmpty()}" else "")
                _progress.value = null
            }
        }
        return true
    }

    fun setPaused(on: Boolean) { paused = on; _progress.value?.let { _progress.value = it.copy(paused = on) } }

    fun stop() { job?.cancel() }
}
