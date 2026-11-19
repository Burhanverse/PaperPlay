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
    fun auto(library: LibraryRepository) = library.pref(AUTO) == "true"
    fun autoCopy(library: LibraryRepository) = library.pref(AUTO_COPY) == "true"

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
    chat_id = chatId, message_id = messageId, unique_id = uniqueId, remote_id = "", title = title, artist = artist, album = "",
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
    private val _progress = MutableStateFlow<IndexProgress?>(null)
    private val runLock = Mutex()

    /** Called after a read finished, for example to save the index to Telegram. */
    var onRunFinished: (suspend () -> Unit)? = null

    override val indexedChats: StateFlow<Map<Long, String>> = _chats
    override val progress: StateFlow<IndexProgress?> = _progress

    override suspend fun indexChat(chat: ChatInfo, full: Boolean) {
        runLock.withLock {
            val had = store.chat(chat.id)
            store.saveChat(chat.id, chat.title)
            _chats.value = store.chats().associate { it.id to it.title }
            // Already indexed: only what is new, or the rest of a stopped read; never the whole chat again unless asked.
            if (full) read(chat.id, chat.title, after = 0, complete = true) else readFromCheckpoint(had ?: IndexedChat(chat.id, chat.title, 0, 0, false))
            mainChat() // finds and saves the main chat when this was it
        }
        onRunFinished?.invoke()
    }

    @Volatile private var stopGen = 0
    private class StopRun : RuntimeException()

    override fun stopRun() { stopGen++ }

    override suspend fun refresh() {
        if (!account.loggedIn.value) return
        runLock.withLock {
            val gen = stopGen
            for (c in store.chats()) {
                if (stopGen != gen) break
                readFromCheckpoint(c)
            }
        }
        onRunFinished?.invoke()
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

    private suspend fun read(chatId: Long, title: String, after: Long, complete: Boolean, resume: ReadCheckpoint? = null) {
        var found = 0
        val ids = HashSet<Long>()
        var newest = 0L
        _progress.value = IndexProgress(chatId, title, 0, true)
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
                        if (stopGen != gen) throw StopRun()
                        if (batch.isEmpty()) return@collect
                        store.upsertPosts(batch)
                        batch.forEach { ids += it.messageId }
                        found += batch.size
                        newest = maxOf(newest, batch.maxOf { it.messageId })
                        // Newest is saved at once but the chat only counts as complete when the whole read ends.
                        store.setState(chatId, newest, clock(), complete = after > 0 && complete)
                        _progress.value = IndexProgress(chatId, title, found, true)
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
        // While a chat is being read the index changes every batch; the list is rebuilt at most about twice a second, not on each one.
        combine(store.changes, library.changes) { _, _ -> }.debounce(400).map {
            val main: Main? = libraryMain()
            val mainId = mainChat()
            val copied = store.copiedIds()
            // Tracks that imported Spotify playlists still wait for, by loose title and artist.
            val lists = library.repo.db.playlistsQueries
            val wantedKeys = lists.livePlaylists().executeAsList()
                .flatMap { pl -> lists.missingTracks(pl.id).executeAsList() }
                .map { looseTitleKey(it.title, it.artist) }.toSet()
            store.posts(chatId).map { p ->
                val have = main?.has(p) == true
                val mine = if (have && p.uniqueId !in (main?.ids ?: emptySet())) main?.match(p) else null
                val own = p.chatId == mainId
                // A post in the main chat is already in your channel; comparing it with a library copy is not a "better" file to fetch.
                val better = !own && mine != null && io.max.paperplay.core.dupes.DefaultSongRules.compareQuality(p.asSong(), mine) < 0
                IndexedSong(p, inMainChat = have, copied = p.uniqueId in copied, better = better, betterWhy = if (better && mine != null) betterReason(p.asSong(), mine) else null,
                    wanted = looseTitleKey(p.title, p.artist) in wantedKeys, ownChat = own)
            }
        }.flowOn(Dispatchers.Default)

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

    private var keyed: Triple<Long, Long, Map<String, List<ChatPost>>>? = null

    /**
     * Posts by loose title and artist. While a chat is being read the index changes every batch, so a map is reused for 15 seconds
     * even when it has changed, and only one caller builds it (the missing-songs sheet asks once per row).
     */
    @Synchronized
    private fun byKey(): Map<String, List<ChatPost>> {
        val stamp = store.changes.value
        val now = clock()
        keyed?.takeIf { it.first == stamp || now - it.second < 15_000 }?.let { return it.third }
        return store.allPosts().groupBy { looseTitleKey(it.title, it.artist) }.also { keyed = Triple(stamp, now, it) }
    }

    override suspend fun matchesFor(title: String, artist: String, durationSec: Int): List<ChatPost> {
        val key = looseTitleKey(title, artist)
        if (key.isEmpty()) return emptyList()
        val main = main()
        val copied = store.copiedIds()
        return byKey()[key].orEmpty()
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
        store.chat(chatId)?.let { ChatIndexDetails(store.count(chatId), it.lastRefreshAt, it.complete) }

    override fun forget(chatId: Long) {
        store.deleteChat(chatId)
        _chats.value = store.chats().associate { it.id to it.title }
    }

    override suspend fun export(): String = buildJsonObject {
        put("version", 1)
        put("chats", buildJsonArray {
            for (c in store.chats()) add(buildJsonObject {
                put("id", c.id); put("title", c.title); put("newest", c.newestMessageId); put("refreshed", c.lastRefreshAt); put("complete", c.complete)
            })
        })
        put("posts", buildJsonArray {
            for (p in store.allPosts()) add(buildJsonArray {
                add(JsonPrimitive(p.chatId)); add(JsonPrimitive(p.messageId)); add(JsonPrimitive(p.uniqueId)); add(JsonPrimitive(p.title))
                add(JsonPrimitive(p.artist)); add(JsonPrimitive(p.durationSec)); add(JsonPrimitive(p.size)); add(JsonPrimitive(p.mime))
                add(JsonPrimitive(p.fileName)); add(JsonPrimitive(p.date))
            })
        })
        put("copied", buildJsonArray {
            for (c in store.copiedRows()) add(buildJsonArray {
                add(JsonPrimitive(c.uniqueId)); add(JsonPrimitive(c.chatId)); add(JsonPrimitive(c.messageId)); add(JsonPrimitive(c.at))
            })
        })
    }.toString()

    /** Merges an exported index in. Saved posts stay; the newer position and a finished read win. Safe to repeat. */
    override suspend fun import(json: String) {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return
        val mine = store.chats().associateBy { it.id }
        for (c in root["chats"] as? JsonArray ?: JsonArray(emptyList())) {
            val o = c as? JsonObject ?: continue
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: continue
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: continue
            store.saveChat(id, title)
            val had = mine[id]
            val newest = maxOf((o["newest"] as? JsonPrimitive)?.longOrNull ?: 0L, had?.newestMessageId ?: 0L)
            val complete = (o["complete"] as? JsonPrimitive)?.contentOrNull == "true" || had?.complete == true
            store.setState(id, newest, maxOf((o["refreshed"] as? JsonPrimitive)?.longOrNull ?: 0L, had?.lastRefreshAt ?: 0L), complete)
        }
        val posts = (root["posts"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonArray ?: return@mapNotNull null
            runCatching {
                fun s(i: Int) = (a[i] as JsonPrimitive).content
                fun n(i: Int) = s(i).toLong()
                ChatPost(n(0), n(1), s(2), s(3), s(4), n(5).toInt(), n(6), s(7), s(8), n(9))
            }.getOrNull()
        }
        if (posts.isNotEmpty()) store.upsertPosts(posts)
        for (e in (root["copied"] as? JsonArray).orEmpty()) {
            val a = e as? JsonArray ?: continue
            runCatching {
                store.markCopied(ChatPost((a[1] as JsonPrimitive).content.toLong(), (a[2] as JsonPrimitive).content.toLong(), (a[0] as JsonPrimitive).content, "", "", 0, 0, "", "", 0), (a[3] as JsonPrimitive).content.toLong())
            }
        }
        _chats.value = store.chats().associate { it.id to it.title }
    }
}

/**
 * Saves the index to the account's Saved Messages and brings it back, so a later login, logout or reinstall can pick
 * it up. Only titles, artists and ids are stored: never tokens or the session.
 */
class IndexCloud(private val account: AccountApi, private val index: ChatIndexApi, private val library: LibraryRepository) {
    private val tag = "paperplay-index-v1"

    suspend fun save() {
        if (!account.loggedIn.value) return
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
