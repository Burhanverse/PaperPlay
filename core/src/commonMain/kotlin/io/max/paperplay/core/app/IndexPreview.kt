package io.max.paperplay.core.app

import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.asSong
import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.library.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a song of the chat index without adding it to the library. The account streams it; the tiny cover Telegram keeps in the
 * post is written to a temporary file. When nothing is playing and [ttlMs] has passed, previewed songs that did not reach the
 * library lose their cover file and the account's downloaded audio is cleared.
 */
class IndexPreview(
    private val dir: String,
    private val inLibrary: (String) -> Boolean,
    private val playingId: () -> String?,
    private val busy: () -> Boolean,
    private val clearDownloads: suspend () -> Unit,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMs: Long = 10 * 60_000L,
) {
    private val seen = HashMap<String, Long>()

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                sweep()
            }
        }
    }

    /** The post as a song the player can queue: streamed by the account, cover from the post's own thumbnail. */
    fun song(post: ChatPost): Song {
        val cover = post.thumb?.takeIf { it.isNotEmpty() }?.let { bytes ->
            val path = "$dir/${post.uniqueId.filter { it.isLetterOrDigit() }}.jpg"
            runCatching { Fs.mkdirs(dir); if (!Fs.exists(path)) Fs.write(path, bytes); path }.getOrNull()
        }
        synchronized(seen) { seen[post.uniqueId] = clock() }
        return post.asSong().copy(remote_id = ACCOUNT_REMOTE, cover_path = cover)
    }

    fun isPreview(uniqueId: String): Boolean = synchronized(seen) { uniqueId in seen } && !inLibrary(uniqueId)

    private suspend fun sweep() {
        val now = clock()
        val playing = playingId()
        val old = synchronized(seen) {
            if (playing != null && playing in seen) seen[playing] = now
            seen.filterValues { now - it > ttlMs }.keys.toList()
        }
        if (old.isEmpty() || busy()) return
        // chisle: the account's downloads are cleared whole; TDLib cannot name one preview's file after a restart.
        if (old.any { !inLibrary(it) }) runCatching { clearDownloads() }
        old.forEach { id ->
            if (!inLibrary(id)) runCatching { Fs.deleteRecursively("$dir/${id.filter { it.isLetterOrDigit() }}.jpg") }
            synchronized(seen) { seen.remove(id) }
        }
    }
}
