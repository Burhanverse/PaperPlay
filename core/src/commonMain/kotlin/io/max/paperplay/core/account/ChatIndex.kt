package io.max.paperplay.core.account

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.integrations.MatchKind
import io.max.paperplay.core.integrations.looseTitleKey
import io.max.paperplay.core.integrations.matchKind
import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Library prefs of the index (read and written through [LibraryRepository.pref]):
 * - `index_auto`: "true" refreshes the index on start and every few hours. Default off.
 * - `index_auto_copy`: "true" copies songs found in the index into the main chat on their own. Default off.
 */
object IndexPrefs {
    const val AUTO = "index_auto"
    const val AUTO_COPY = "index_auto_copy"
    const val PAUSED = "index_paused"
    fun auto(library: LibraryRepository) = library.pref(AUTO) == "true"
    /** On unless turned off: songs a Spotify playlist waits for are fetched from the index as soon as they are found. */
    fun autoCopy(library: LibraryRepository) = library.pref(AUTO_COPY) != "false"

    /** Per channel: "true" lets automatic copying take songs from this added channel into the main chat. Default off. */
    fun channelKey(chatId: Long) = "channel_copy:$chatId"

    /**
     * Whether automatic copying may take songs from [chatId]. Chats that are not added channels of the library
     * (the account's other chats) and the main chat itself are not limited; an added channel needs its switch on.
     */
    fun channelCopyAllowed(library: LibraryRepository, chatId: Long, mainChat: Long?): Boolean =
        chatId == mainChat || library.chat(chatId) == null || library.pref(channelKey(chatId)) == "true"
}

/** A [Song] row built from a post, so the shared matching rules can be applied to it. */
fun ChatPost.asSong(): Song = Song(
    chat_id = chatId, message_id = messageId, unique_id = uniqueId, remote_id = "", title = title, artist = artist, album = album,
    duration_sec = durationSec.toLong(), size = size, mime = mime, file_name = fileName, date = date,
    cover_path = null, release_id = null, cover_url = null, genre = null, year = null, metadata_source = null,
    favorite = false, cover_hi_res = false, user_edited = false, enrich_state = 0, lyrics = null,
    cached_bytes = 0, last_played_at = null, pinned = false, tags_at = 0, favorite_at = 0, cover_at = 0,
    instrumental = false, mood_scanned_at = 0, plays = 0, skips = 0, topic_id = null,
    sample_rate = null, bit_depth = null, codec = null,
)

/**
 * The saved index of the account's chats. Posts are written as they arrive, so screens and copying work while a
 * read is still going on. A refresh reads only what is newer than the newest post saved.
 */
class ChatIndex(
    private val account: AccountApi,
    private val store: IndexStore,
    private val library: LibraryRepository,
    private val configuredMain: () -> Long?,
    private val clock: () -> Long,
) : ChatIndexApi {
    init {
        // Earlier builds marked a post as copied even when Telegram refused to copy it; those marks are wrong.
        if (library.pref("index_copied_reset") != "1") {
            store.clearCopied()
            library.setPref("index_copied_reset", "1")
        }
    }

    /**
     * The main chat: the one the user chose, else the library chat that is also indexed (the one with most library songs). When it is found
     * this way it is saved as the choice, so indexing the main channel is enough for the app to know it.
     */
    private fun mainChat(): Long? {
        configuredMain()?.let { return it }
        val indexed = store.chats().map { it.id }.toSet()
        val pick = library.chatList().map { it.id }.filter { it in indexed }.maxByOrNull { library.songsInChat(it).size } ?: return null
        library.setPref(io.max.paperplay.core.filing.MAIN_CHAT_KEY, """{"id":$pick,"at":${clock()}}""")
        return pick
    }

    private val _chats = MutableStateFlow(store.chats().associate { it.id to it.title })
    private val _chatRows = MutableStateFlow(store.chats())
    private val _progress = MutableStateFlow<IndexProgress?>(null)
    private val _paused = MutableStateFlow(library.pref(IndexPrefs.PAUSED) == "true")
    private val runLock = Mutex()

    override val chats: StateFlow<List<IndexedChat>> = _chatRows
    override val changes: StateFlow<Long> = store.changes
    override val paused: StateFlow<Boolean> = _paused

    /** Called with the new pause state, so a linked device can follow it. */
    var onPausedChanged: ((Boolean) -> Unit)? = null

    override fun setPaused(paused: Boolean) {
        if (paused) stopRun()
        if (_paused.value == paused) return
        _paused.value = paused
        library.setPref(IndexPrefs.PAUSED, "$paused")
        onPausedChanged?.invoke(paused)
    }

    private fun chatsChanged() {
        val rows = store.chats()
        _chatRows.value = rows
        _chats.value = rows.associate { it.id to it.title }
    }

    /** Called after a read finished, for example to save the index to Telegram. */
    var onRunFinished: (suspend () -> Unit)? = null

    override val indexedChats: StateFlow<Map<Long, String>> = _chats
    override val progress: StateFlow<IndexProgress?> = _progress

    override suspend fun indexChat(chat: ChatInfo, full: Boolean) {
        runLock.withLock {
            // Starting a read by hand is also a resume.
            setPaused(false)
            val had = store.chat(chat.id)
            store.saveChat(chat.id, chat.title, clock())
            saveLinks(chat.id, chat.isChannel)
            chatsChanged()
            // Already indexed: only what is new, or the rest of a stopped read; never the whole chat again unless asked.
            if (full) read(chat.id, chat.title, after = 0, complete = true) else readFromCheckpoint(had ?: IndexedChat(chat.id, chat.title, 0, 0, false))
            mainChat() // finds and saves the main chat when this was it
            chatsChanged()
        }
        onRunFinished?.invoke()
    }

    @Volatile private var stopGen = 0
    private class StopRun : RuntimeException()

    override fun stopRun() { stopGen++ }

    override suspend fun refresh() {
        if (!account.loggedIn.value || _paused.value) return
        runLock.withLock {
            val gen = stopGen
            for (c in store.chats()) {
                if (stopGen != gen || _paused.value) break
                // Chats from someone else's file are read only after joining them.
                if (!c.joined) continue
                // One chat that fails (left, banned, gone) does not stop the others.
                try {
                    if (c.username.isEmpty() && c.inviteLink.isEmpty()) saveLinks(c.id, c.isChannel)
                    readFromCheckpoint(c)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = "${c.title}: ${e.message}"
                }
            }
            chatsChanged()
        }
        onRunFinished?.invoke()
    }

    /** The last error of a refresh, for the log; one failing chat no longer stops the rest. */
    @kotlin.concurrent.Volatile var lastError: String? = null

    /** Saves how others can join [chatId]; failures leave it unknown. */
    private suspend fun saveLinks(chatId: Long, isChannel: Boolean) {
        val l = runCatching { account.chatLinks(chatId) }.getOrNull()
        store.setChatInfo(chatId, l?.username.orEmpty(), l?.inviteLink.orEmpty(), l?.isChannel ?: isChannel, joined = true, at = clock())
    }

    private fun ckptKey(chatId: Long) = "index_ckpt:$chatId"

    /** A finished chat is read for newer posts only; one whose read was stopped goes on from its saved page cursor. */
    private suspend fun readFromCheckpoint(c: IndexedChat) {
        if (c.complete) { read(c.id, c.title, after = c.newestMessageId, complete = true); return }
        val saved = library.pref(ckptKey(c.id))?.split(":")
        // No saved cursor (a read made by an older build) but posts are stored: go on below the oldest stored post instead of starting over.
        val oldest = if (c.newestMessageId > 0) store.oldestMessageId(c.id) else 0L
        val cp = when {
            saved?.size == 2 && c.newestMessageId > 0 -> ReadCheckpoint(saved[0].toIntOrNull() ?: 0, saved[1].toLongOrNull() ?: 0L)
            oldest > 0 -> ReadCheckpoint(0, oldest)
            else -> null
        }
        // Newer posts that arrived since the stop are caught first, then the stopped read goes on from where it ended.
        if (cp != null) {
            read(c.id, c.title, after = c.newestMessageId, complete = false)
            read(c.id, c.title, after = 0, complete = true, resume = cp)
        } else read(c.id, c.title, after = 0, complete = true)
    }

    /** Message ids the last full read of each chat saw in this session. */
    @kotlin.concurrent.Volatile private var fullRead: Map<Long, Set<Long>> = emptyMap()

    /** Called after a batch added or changed posts, for example to send them to a linked device. */
    var onPostsSaved: (() -> Unit)? = null

    private suspend fun read(chatId: Long, title: String, after: Long, complete: Boolean, resume: ReadCheckpoint? = null) {
        var found = 0
        val ids = HashSet<Long>()
        var newest = 0L
        _progress.value = IndexProgress(chatId, title, store.count(chatId), true)
        // A new full read replaces the old snapshot; if it is stopped or fails, no snapshot remains and nothing is reconciled.
        if (after == 0L && resume == null) fullRead = fullRead - chatId
        // A full read keeps its page cursor, so stopping it (or closing the app) loses no work.
        val cp = resume ?: if (after == 0L) ReadCheckpoint() else null
        val gen = stopGen
        try {
            kotlinx.coroutines.coroutineScope {
                // The shown count is the real count, updated as each batch lands; an estimate between batches ran ahead and then dropped back.
                run {
                    account.songsIn(chatId, after, cp).collect { batch ->
                        // Checked on every page, empty ones too, so a pause takes effect within one page.
                        if (stopGen != gen) throw StopRun()
                        if (batch.isEmpty()) return@collect
                        if (store.upsertPosts(batch, clock()) > 0) onPostsSaved?.invoke()
                        batch.forEach { ids += it.messageId }
                        found += batch.size
                        newest = maxOf(newest, batch.maxOf { it.messageId })
                        // Newest is saved at once but the chat only counts as complete when the whole read ends.
                        store.setState(chatId, newest, clock(), complete = after > 0 && complete)
                        // The count shown is everything saved for the chat, so a resumed read goes on from 78,700, not from 0.
                        _progress.value = IndexProgress(chatId, title, store.count(chatId), true)
                        cp?.let { library.setPref(ckptKey(chatId), "${it.filter}:${it.from}") }
                    }
                }
            }
            store.setState(chatId, newest, clock(), complete)
            if (cp != null) library.setPref(ckptKey(chatId), "")
            if (after == 0L && resume == null) fullRead = fullRead + (chatId to ids)
        } catch (_: StopRun) {
            // Stopped by the owner; what was read is kept and the chat stays marked as not complete.
        } finally {
            _progress.value = null
        }
    }

    private class Main(val ids: Set<String>, val byKey: Map<String, List<Song>>) {
        /** The library's copy of the same recording, if any. */
        fun match(p: ChatPost): Song? =
            byKey[looseTitleKey(p.title, p.artist)].orEmpty().firstOrNull { matchKind(it, p.title, p.artist, p.durationSec) == MatchKind.Sure }

        fun has(p: ChatPost): Boolean = p.uniqueId in ids ||
            byKey[looseTitleKey(p.title, p.artist)].orEmpty().any { matchKind(it, p.title, p.artist, p.durationSec) == MatchKind.Sure }
    }

    private fun main(): Main? {
        val m = mainChat() ?: return null
        val songs = library.songsInChat(m)
        val copies = library.repo.db.filingQueries.allPostCopies().executeAsList().filter { it.chat_id == m }
        return Main(songs.map { it.unique_id }.toSet() + copies.map { it.unique_id }, songs.groupBy { looseTitleKey(it.title, it.artist) })
    }

    /** The whole library, not only the main chat: a song added from any chat counts as already there. */
    private fun libraryMain(): Main {
        // A library-only entry (played through the account, no post in Telegram) is not "had": it stays listed until it is really copied.
        val all = library.repo.db.songsQueries.allSongs().executeAsList().filter { it.remote_id != io.max.paperplay.core.app.ACCOUNT_REMOTE }
        val copies = library.repo.db.filingQueries.allPostCopies().executeAsList()
        return Main(all.map { it.unique_id }.toSet() + copies.map { it.unique_id }, all.groupBy { looseTitleKey(it.title, it.artist) })
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun songs(chatId: Long): Flow<List<IndexedSong>> =
        // Kept for small callers and tests; the chat page reads pages instead.
        combine(store.changes, library.changes) { _, _ -> }.debounce(400).map {
            updateFlags()
            store.page(chatId, IndexFilter.All, "", 0, Int.MAX_VALUE, mainChat())
        }.flowOn(Dispatchers.Default)

    override fun page(chatId: Long, filter: IndexFilter, query: String, offset: Int, limit: Int): List<IndexedSong> =
        store.page(chatId, filter, query, offset, limit, configuredMain())

    override fun counts(chatId: Long, query: String): List<Int> = store.counts(chatId, query)

    override fun select(chatId: Long, filter: IndexFilter, query: String): List<ChatPost> = store.select(chatId, filter, query)

    override fun postsByIds(chatId: Long, ids: Collection<Long>): List<ChatPost> = store.postsByIds(chatId, ids)

    override fun thumb(chatId: Long, messageId: Long): ByteArray? = store.thumb(chatId, messageId)

    override fun searchAll(query: String, limit: Int): List<ChatPost> = store.searchAll(query, limit)

    private val flagsLock = Mutex()
    private var flagsInput: Any? = null
    private var flagsStamp = -1L

    /**
     * Works the library flags of the posts out again, but only when what they depend on changed: the songs the library has (not their
     * play counts), the tracks Spotify playlists wait for, or the posts themselves.
     */
    override suspend fun updateFlags() = flagsLock.withLock {
        store.prepare()
        val all = library.repo.db.songsQueries.allSongs().executeAsList()
        val real = all.filter { it.remote_id != io.max.paperplay.core.app.ACCOUNT_REMOTE }
        val ids = real.mapTo(HashSet()) { it.unique_id } + library.repo.db.filingQueries.allPostCopies().executeAsList().map { it.unique_id }
        val byKey = real.groupBy { looseTitleKey(it.title, it.artist) }.filterKeys { it.isNotEmpty() }
        val lists = library.repo.db.playlistsQueries
        val wanted = lists.livePlaylists().executeAsList()
            .flatMap { pl -> lists.missingTracks(pl.id).executeAsList() }
            .map { looseTitleKey(it.title, it.artist) }.filter { it.isNotEmpty() }.toSet()
        val mainId = mainChat()
        val input = listOf(ids, byKey.mapValues { (_, v) -> v.map { Triple(it.unique_id, it.size, it.duration_sec) } }, wanted, mainId)
        if (input == flagsInput && store.changes.value == flagsStamp) return@withLock
        store.refreshFlags(ids, byKey.keys, wanted) { p ->
            val mine = byKey[looseTitleKey(p.title, p.artist)].orEmpty().firstOrNull { matchKind(it, p.title, p.artist, p.durationSec) == MatchKind.Sure }
                ?: return@refreshFlags false to null
            // A post in the main chat is already in your channel; comparing it with a library copy is not a "better" file to fetch.
            val better = p.chatId != mainId && io.max.paperplay.core.dupes.DefaultSongRules.compareQuality(p.asSong(), mine) < 0
            true to (if (better) betterReason(p.asSong(), mine) else null)
        }
        flagsInput = input
        flagsStamp = store.changes.value
    }

    /** What makes [post] better than [mine], following the order [DefaultSongRules.compareQuality] uses. */
    private fun betterReason(post: Song, mine: Song): String {
        val rules = io.max.paperplay.core.dupes.DefaultSongRules
        val a = rules.format(post)
        val b = rules.format(mine)
        fun mb(n: Long) = if (n >= 1L shl 20) "${n / (1L shl 20)} MB" else "${n / 1024} KB"
        return when {
            rules.isLossless(a) && !rules.isLossless(b) -> "$a instead of your $b"
            else -> "$a, ${mb(post.size)} vs your $b, ${mb(mine.size)}" +
                (mine.bit_depth?.let { ", yours is $it-bit" } ?: "") + (mine.sample_rate?.let { ", ${it / 1000.0} kHz" } ?: "")
        }
    }

    override suspend fun matchesFor(title: String, artist: String, durationSec: Int): List<ChatPost> {
        val key = looseTitleKey(title, artist)
        if (key.isEmpty()) return emptyList()
        val main = main()
        val copied = store.copiedIds()
        return store.postsWithKey(key)
            .mapNotNull { p -> matchKind(p.asSong(), title, artist, durationSec)?.let { p to it } }
            .filter { (p, _) -> p.uniqueId !in copied && main?.has(p) != true }
            .sortedWith(compareBy({ it.second != MatchKind.Sure }, { abs(it.first.durationSec - durationSec) }, { -it.first.size }))
            .map { it.first }
    }

    override suspend fun copyToMain(post: ChatPost) {
        val main = mainChat() ?: throw IllegalStateException("No main chat is set.")
        account.copyPost(post.chatId, post.messageId, main)
        store.markCopied(post, clock())
    }

    override suspend fun addToLibrary(post: ChatPost) {
        // Already in the library (this file, listed or as another post): nothing is added twice.
        if (library.songByUniqueId(post.uniqueId) != null) return
        library.upsertBotSong(post.asSong().copy(remote_id = io.max.paperplay.core.app.ACCOUNT_REMOTE))
    }

    override suspend fun missingFromMainChat(): List<ChatPost> {
        val chat = mainChat() ?: return emptyList()
        // By message id only: the library keeps every post of the chat, repeats included, so duplicates can be found and deleted.
        val have = knownPostIds(chat)
        return store.posts(chat).filter { it.messageId !in have }
    }

    override suspend fun addPostsNotInLibrary(): MainChatAdd {
        val chat = mainChat() ?: return MainChatAdd(0, 0, 0)
        val seen = fullRead[chat] ?: return MainChatAdd(0, 0, 0)
        val have = knownPostIds(chat)
        val missing = store.posts(chat).filter { it.messageId in seen && it.messageId !in have }
        missing.forEach { addToLibrary(it) }
        val repeats = library.repo.db.filingQueries.allPostCopies().executeAsList().count { it.chat_id == chat }
        return MainChatAdd(seen.size, missing.size, repeats)
    }

    /** Posts the library already holds: the listed song of each file, and its other posts (repeats) kept as copies. */
    private fun knownPostIds(chat: Long): Set<Long> =
        library.songsInChat(chat).map { it.message_id }.toSet() +
            library.repo.db.filingQueries.allPostCopies().executeAsList().filter { it.chat_id == chat }.map { it.message_id }

    override suspend fun pruneGoneFromMainChat(): Int {
        val chat = mainChat() ?: return -1
        val seen = fullRead[chat] ?: return -1
        val rows = library.songsInChat(chat)
        val copies = library.repo.db.filingQueries.allPostCopies().executeAsList().filter { it.chat_id == chat }
        // Posts newer than the newest one the read saw may have arrived after it; they are not judged.
        val newest = seen.maxOrNull() ?: return -1
        val gone = rows.filter { it.message_id !in seen && it.message_id <= newest }
        val goneCopies = copies.filter { it.message_id !in seen && it.message_id <= newest }
        // A read that saw only a part of the chat must not wipe the library (listed posts and repeats counted together).
        if (gone.size + goneCopies.size > (rows.size + copies.size) / 2) return -2
        // Missing repeats go first, so a missing listed post is never replaced by a repeat that is missing too.
        for (c in goneCopies) library.removePost(c.chat_id, c.message_id)
        for (s in gone) library.removePost(s.chat_id, s.message_id)
        return gone.size
    }

    override suspend fun addMissingFromMainChat(): Int {
        val missing = missingFromMainChat()
        missing.forEach { addToLibrary(it) }
        return missing.size
    }

    private fun libraryOnly(): List<Song> {
        val main = mainChat()
        // Includes posts that sit in the main chat but that no bot can read: copying posts them again from the account, and the old post becomes a repeat.
        return library.repo.db.songsQueries.allSongs().executeAsList().filter { it.remote_id == io.max.paperplay.core.app.ACCOUNT_REMOTE }
    }

    override fun libraryOnlyCount(): Int = libraryOnly().size

    override suspend fun clearDeletedLibraryOnly(): Triple<Int, Int, Int> {
        var removed = 0
        var kept = 0
        var unchecked = 0
        for ((chat, rows) in libraryOnly().groupBy { it.chat_id }) {
            // Only a post Telegram says is gone is removed; if it cannot be asked, the songs stay.
            val alive = runCatching { account.existingPosts(chat, rows.map { it.message_id }) }.getOrNull()
            if (alive == null) { unchecked += rows.size; continue }
            for (r in rows) if (r.message_id in alive) kept++ else { library.removePost(r.chat_id, r.message_id); removed++ }
        }
        return Triple(removed, kept, unchecked)
    }

    override suspend fun copyLibraryOnly(delayMs: Long, paused: () -> Boolean, onStep: (Int, Int, Int, String?) -> Unit) =
        copyAll(libraryOnly(), delayMs, paused, onStep)

    override suspend fun copySongsToMain(songs: List<Song>, delayMs: Long, paused: () -> Boolean, onStep: (Int, Int, Int, String?) -> Unit) {
        val main = mainChat()
        // A song whose post is in the main chat and readable needs nothing; one the bot cannot read is posted again by the account.
        copyAll(songs.filter { it.chat_id != main || it.remote_id == io.max.paperplay.core.app.ACCOUNT_REMOTE }, delayMs, paused, onStep)
    }

    private suspend fun copyAll(todo: List<Song>, delayMs: Long, paused: () -> Boolean, onStep: (Int, Int, Int, String?) -> Unit) {
        var done = 0
        var failed = 0
        var why: String? = null
        todo.forEachIndexed { i, s ->
            while (paused()) { onStep(done, failed, todo.size, why); kotlinx.coroutines.delay(300) }
            val post = ChatPost(
                chatId = s.chat_id, messageId = s.message_id, uniqueId = s.unique_id, title = s.title, artist = s.artist,
                durationSec = s.duration_sec.toInt(), size = s.size, mime = s.mime, fileName = s.file_name, date = s.date,
            )
            runCatching { copyToMain(post) }.onSuccess { done++ }.onFailure { failed++; if (why == null) why = it.message }
            onStep(done, failed, todo.size, why)
            if (i < todo.size - 1) kotlinx.coroutines.delay(delayMs)
        }
    }

    override fun details(chatId: Long): ChatIndexDetails? =
        _chatRows.value.firstOrNull { it.id == chatId }?.let { ChatIndexDetails(it.postCount, it.lastRefreshAt, it.complete) }

    override fun forget(chatId: Long) {
        store.deleteChat(chatId)
        library.setPref(ckptKey(chatId), "")
        chatsChanged()
    }

    override suspend fun join(chatId: Long): Boolean {
        val c = store.chat(chatId) ?: return false
        val link = c.publicLink ?: return false
        val id = runCatching { account.joinChat(link) }.getOrNull() ?: return false
        if (id != chatId) return false
        store.setJoined(chatId, true)
        chatsChanged()
        return true
    }

    override suspend fun export(): String = StringBuilder().also { IndexFile.write(store, it, null, forSharing = false, clock()) }.toString()

    override suspend fun exportTo(out: Appendable, chatIds: Set<Long>?) = IndexFile.write(store, out, chatIds, forSharing = true, clock())

    /** Merges an exported index in (the account's own cloud copy). Saved posts stay; the newer position and a finished read win. Safe to repeat. */
    override suspend fun import(json: String) {
        IndexFile.read(json, store, clock(), own = true)
        chatsChanged()
    }

    /** Merges someone else's (or another device's) file: copied marks stay theirs, and chats this account cannot see are marked to join. */
    override suspend fun importFrom(text: String): IndexImport {
        val fresh = ArrayList<Long>()
        val r = IndexFile.read(text, store, clock(), own = false, fresh)
        // A chat the account is already in needs no joining.
        var notJoined = r.notJoined
        for (id in fresh) if (runCatching { account.canSee(id) }.getOrDefault(false)) { store.setJoined(id, true); notJoined-- }
        chatsChanged()
        return r.copy(notJoined = notJoined)
    }
}

/**
 * Saves the index to the account's Saved Messages and brings it back, so a later login, logout or reinstall can pick
 * it up. Only titles, artists and ids are stored: never tokens or the session.
 */
class IndexCloud(private val account: AccountApi, private val index: ChatIndexApi, private val library: LibraryRepository) {
    private val tag = "paperplay-index-v1"

    /**
     * Saves the index to Saved Messages when it changed since the last save, at most once a day ([force] skips the wait, not the change
     * check). A big index is tens of MB, so it is not sent again after every read.
     */
    suspend fun save(force: Boolean = false) {
        if (!account.loggedIn.value) return
        val sig = index.chats.value.joinToString(";") { "${it.id}:${it.postCount}:${it.newestMessageId}:${it.complete}" }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val last = library.pref("index_cloud_at")?.toLongOrNull() ?: 0L
        if (sig == library.pref("index_cloud_sig") || (!force && now - last < 24 * 3600_000L)) return
        val body = buildJsonObject {
            put("index", Json.parseToJsonElement(index.export()))
            put("prefs", buildJsonObject {
                put(IndexPrefs.AUTO, library.pref(IndexPrefs.AUTO) ?: "false")
                put(IndexPrefs.AUTO_COPY, library.pref(IndexPrefs.AUTO_COPY) ?: "false")
            })
            put("version", 2)
            put("user", userData())
        }.toString()
        account.saveToCloud(tag, "paperplay-index.json", body.encodeToByteArray())
        library.setPref("index_cloud_at", "$now")
        library.setPref("index_cloud_sig", sig)
    }

    /** Favorites, listening counts, edited tags, pins and playlists, keyed by unique_id. */
    private fun userData(): JsonObject {
        val q = library.repo.db
        val rows = q.songsQueries.allSongs().executeAsList()
        return buildJsonObject {
            put("songs", buildJsonArray {
                for (s in rows) {
                    if (!(s.favorite || s.plays > 0 || s.skips > 0 || s.last_played_at != null || s.user_edited || s.pinned)) continue
                    add(buildJsonObject {
                        put("id", s.unique_id); put("fav", s.favorite); put("favAt", s.favorite_at); put("plays", s.plays); put("skips", s.skips)
                        s.last_played_at?.let { put("last", it) }
                        put("pinned", s.pinned); put("edited", s.user_edited)
                        if (s.user_edited) {
                            put("title", s.title); put("artist", s.artist); put("album", s.album)
                            s.genre?.let { put("genre", it) }; s.year?.let { put("year", it) }
                        }
                    })
                }
            })
            put("playlists", buildJsonArray {
                for (p in q.playlistsQueries.livePlaylists().executeAsList()) add(buildJsonObject {
                    put("name", p.name)
                    put("songs", buildJsonArray {
                        for (s in q.playlistsQueries.playlistSongs(p.id).executeAsList()) add(JsonPrimitive(s.unique_id))
                    })
                })
            })
        }
    }

    /** Merges saved user data into matching songs: newer favorite wins, counts and last play take the larger value. */
    private fun applyUserData(user: JsonObject) {
        val q = library.repo.db
        val local = q.songsQueries.allSongs().executeAsList().associateBy { it.unique_id }
        val changed = ArrayList<Song>()
        for (e in user["songs"] as? JsonArray ?: JsonArray(emptyList())) {
            val o = e as? JsonObject ?: continue
            val s = local[(o["id"] as? JsonPrimitive)?.contentOrNull] ?: continue
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            fun b(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull == "true"
            fun t(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            var n = s
            val favAt = l("favAt") ?: 0L
            if (favAt > s.favorite_at) n = n.copy(favorite = b("fav"), favorite_at = favAt)
            n = n.copy(
                plays = maxOf(n.plays, l("plays") ?: 0L), skips = maxOf(n.skips, l("skips") ?: 0L),
                last_played_at = listOfNotNull(n.last_played_at, l("last")).maxOrNull(), pinned = n.pinned || b("pinned"),
            )
            if (b("edited") && !s.user_edited) {
                n = n.copy(
                    user_edited = true, title = t("title") ?: n.title, artist = t("artist") ?: n.artist, album = t("album") ?: n.album,
                    genre = t("genre") ?: n.genre, year = l("year") ?: n.year, tags_at = maxOf(n.tags_at, 1L),
                )
            }
            if (n != s) changed += n
        }
        if (changed.isNotEmpty()) library.replaceSongs(changed)
        val have = q.playlistsQueries.livePlaylists().executeAsList().map { it.name }.toSet()
        for (e in user["playlists"] as? JsonArray ?: JsonArray(emptyList())) {
            val o = e as? JsonObject ?: continue
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (name in have) continue
            val members = (o["songs"] as? JsonArray).orEmpty().mapNotNull { local[(it as? JsonPrimitive)?.contentOrNull] }
            if (members.isEmpty()) continue
            val id = library.createPlaylist(name)
            members.forEach { library.addToPlaylist(id, it) }
        }
    }

    /** True when a snapshot was found and merged. */
    suspend fun restore(): Boolean {
        val bytes = account.loadFromCloud(tag) ?: return false
        val root = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: return false
        val data = root["index"] ?: return false
        index.import(data.toString())
        (root["prefs"] as? JsonObject)?.forEach { (k, v) ->
            if (k == IndexPrefs.AUTO || k == IndexPrefs.AUTO_COPY) (v as? JsonPrimitive)?.contentOrNull?.let { library.setPref(k, it) }
        }
        (root["user"] as? JsonObject)?.let { runCatching { applyUserData(it) } }
        return true
    }

    /** Restores once after the first login on this install; failures are ignored and tried again next start. */
    suspend fun restoreOnce() {
        if (library.pref("cloud_restored") == "1") return
        runCatching { restore(); library.setPref("cloud_restored", "1") }
    }
}
