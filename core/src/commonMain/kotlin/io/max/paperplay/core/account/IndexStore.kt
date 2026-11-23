package io.max.paperplay.core.account

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.integrations.looseTitleKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is saved about one indexed chat. [complete] is false until a first full read has finished. [username] and [inviteLink] are how
 * someone else can join it (empty when unknown); [joined] is false for chats that came from someone else's index file.
 */
data class IndexedChat(
    val id: Long,
    val title: String,
    val newestMessageId: Long,
    val lastRefreshAt: Long,
    val complete: Boolean,
    val username: String = "",
    val inviteLink: String = "",
    val isChannel: Boolean = true,
    val postCount: Int = 0,
    val joined: Boolean = true,
    val updatedAt: Long = 0,
) {
    /** A link anyone can open: the public name, else the invite link, else none. */
    val publicLink: String? get() = when {
        username.isNotEmpty() -> "https://t.me/$username"
        inviteLink.isNotEmpty() -> inviteLink
        else -> null
    }
}

/** Which posts of a chat a list shows. */
enum class IndexFilter { All, New, Better, Have, Wanted }

/**
 * The saved index: posts of the account's chats, per-chat state and which songs were copied.
 * Plain SQL on the library file, created when first used, so the library schema version is untouched.
 * Nothing here deletes posts except [deleteChat], which only the owner's "Remove from the index" calls.
 *
 * Each post carries flags worked out against the library ([refreshFlags]): `in_library` (0 no, 1 same file, 2 same song), `better`
 * (why this file beats the library's copy) and `wanted` (a Spotify playlist waits for it). Lists, counts and filters run in SQL on those
 * flags, a page at a time, so a chat of 80,000 songs never has to be held in memory.
 */
class IndexStore(private val driver: SqlDriver) {
    /** Counts every write, for flows that re-read. */
    val changes = MutableStateFlow(0L)

    private val db = LibraryDatabase(driver)

    init {
        exec("CREATE TABLE IF NOT EXISTS index_chats (chat_id INTEGER NOT NULL PRIMARY KEY, title TEXT NOT NULL, newest_message_id INTEGER NOT NULL DEFAULT 0, last_refresh_at INTEGER NOT NULL DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0)")
        exec(
            "CREATE TABLE IF NOT EXISTS index_posts (chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, unique_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, " +
                "duration_sec INTEGER NOT NULL, size INTEGER NOT NULL, mime TEXT NOT NULL, file_name TEXT NOT NULL, date INTEGER NOT NULL, PRIMARY KEY (chat_id, message_id))",
        )
        exec("CREATE TABLE IF NOT EXISTS index_copied (unique_id TEXT NOT NULL PRIMARY KEY, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, copied_at INTEGER NOT NULL)")
        addColumns(
            "index_chats",
            "username TEXT NOT NULL DEFAULT ''", "invite_link TEXT NOT NULL DEFAULT ''", "is_channel INTEGER NOT NULL DEFAULT 1",
            "post_count INTEGER NOT NULL DEFAULT 0", "joined INTEGER NOT NULL DEFAULT 1", "updated_at INTEGER NOT NULL DEFAULT 0",
        )
        addColumns(
            "index_posts",
            "album TEXT NOT NULL DEFAULT ''", "thumb BLOB", "loose_key TEXT NOT NULL DEFAULT ''", "in_library INTEGER NOT NULL DEFAULT 0",
            "better TEXT", "wanted INTEGER NOT NULL DEFAULT 0", "updated_at INTEGER NOT NULL DEFAULT 0",
            // The linked device a post came from, so it is never sent back there.
            "src TEXT NOT NULL DEFAULT ''",
        )
        exec("CREATE INDEX IF NOT EXISTS index_posts_unique ON index_posts (unique_id)")
        exec("CREATE INDEX IF NOT EXISTS index_posts_key ON index_posts (loose_key)")
        exec("CREATE INDEX IF NOT EXISTS index_posts_updated ON index_posts (updated_at)")
        // Rows saved before the counts and keys existed get them once.
        exec("UPDATE index_chats SET post_count = (SELECT COUNT(*) FROM index_posts p WHERE p.chat_id = index_chats.chat_id) WHERE post_count = 0")
    }

    private fun addColumns(table: String, vararg defs: String) {
        val have = query("PRAGMA table_info($table)") { it.getString(1)!! }.toSet()
        for (d in defs) if (d.substringBefore(' ') !in have) exec("ALTER TABLE $table ADD COLUMN $d")
    }

    /** Works out the loose title key of posts saved before keys existed, a few thousand at a time. Run off the main thread. */
    fun prepare() {
        while (true) {
            val rows = query("SELECT chat_id, message_id, title, artist FROM index_posts WHERE loose_key = '' LIMIT 5000") {
                Triple(it.getLong(0)!! to it.getLong(1)!!, it.getString(2)!!, it.getString(3)!!)
            }
            if (rows.isEmpty()) return
            db.transaction {
                for ((id, title, artist) in rows) {
                    // A post with no usable title still needs a key, or it would be picked again forever.
                    val key = looseTitleKey(title, artist).ifEmpty { "-" }
                    exec("UPDATE index_posts SET loose_key = ? WHERE chat_id = ? AND message_id = ?", 3) { bindString(0, key); bindLong(1, id.first); bindLong(2, id.second) }
                }
            }
        }
    }

    private fun exec(sql: String, params: Int = 0, binders: (SqlPreparedStatement.() -> Unit)? = null) {
        driver.execute(null, sql, params, binders)
    }

    private fun <T> query(sql: String, params: Int = 0, binders: (SqlPreparedStatement.() -> Unit)? = null, read: (SqlCursor) -> T): List<T> =
        driver.executeQuery(
            null, sql,
            { c ->
                val rows = mutableListOf<T>()
                while (c.next().value) rows += read(c)
                QueryResult.Value(rows)
            },
            params, binders,
        ).value

    private fun touch() = changes.update { it + 1 }

    private val chatColumns = "chat_id, title, newest_message_id, last_refresh_at, complete, username, invite_link, is_channel, post_count, joined, updated_at"

    private fun chatRow(c: SqlCursor) = IndexedChat(
        c.getLong(0)!!, c.getString(1)!!, c.getLong(2)!!, c.getLong(3)!!, c.getLong(4) == 1L,
        c.getString(5)!!, c.getString(6)!!, c.getLong(7) == 1L, c.getLong(8)!!.toInt(), c.getLong(9) == 1L, c.getLong(10)!!,
    )

    fun chats(): List<IndexedChat> = query("SELECT $chatColumns FROM index_chats ORDER BY title", read = ::chatRow)

    fun chat(id: Long): IndexedChat? = query("SELECT $chatColumns FROM index_chats WHERE chat_id = ?", 1, { bindLong(0, id) }, ::chatRow).firstOrNull()

    fun saveChat(id: Long, title: String, at: Long = 0) {
        exec("INSERT INTO index_chats (chat_id, title, updated_at) VALUES (?, ?, ?) ON CONFLICT(chat_id) DO UPDATE SET title = excluded.title, updated_at = MAX(updated_at, excluded.updated_at)", 3) {
            bindLong(0, id); bindString(1, title); bindLong(2, at)
        }
        touch()
    }

    /** How others can find the chat. Empty values never wipe ones already known. */
    fun setChatInfo(id: Long, username: String, inviteLink: String, isChannel: Boolean, joined: Boolean, at: Long) {
        exec(
            "UPDATE index_chats SET username = CASE WHEN ? = '' THEN username ELSE ? END, invite_link = CASE WHEN ? = '' THEN invite_link ELSE ? END, " +
                "is_channel = ?, joined = MAX(joined, ?), updated_at = ? WHERE chat_id = ?", 8,
        ) {
            bindString(0, username); bindString(1, username); bindString(2, inviteLink); bindString(3, inviteLink)
            bindLong(4, if (isChannel) 1 else 0); bindLong(5, if (joined) 1 else 0); bindLong(6, at); bindLong(7, id)
        }
        touch()
    }

    /** Marks a chat as joined or not; joining is the only way to copy from a chat that came from someone else's file. */
    fun setJoined(id: Long, joined: Boolean) {
        exec("UPDATE index_chats SET joined = ? WHERE chat_id = ?", 2) { bindLong(0, if (joined) 1 else 0); bindLong(1, id) }
        touch()
    }

    fun setState(id: Long, newest: Long, refreshedAt: Long, complete: Boolean) {
        exec("UPDATE index_chats SET newest_message_id = MAX(newest_message_id, ?), last_refresh_at = MAX(last_refresh_at, ?), complete = ?, updated_at = MAX(updated_at, ?) WHERE chat_id = ?", 5) {
            bindLong(0, newest); bindLong(1, refreshedAt); bindLong(2, if (complete) 1 else 0); bindLong(3, refreshedAt); bindLong(4, id)
        }
        touch()
    }

    /**
     * Saves [posts] in one transaction. A post that is already saved and unchanged is left alone (so it is not sent to the other device
     * again); a changed one gets [at] as its time. Returns how many were new or changed.
     */
    fun upsertPosts(posts: List<ChatPost>, at: Long = 0): Int {
        if (posts.isEmpty()) return 0
        var changed = 0
        db.transaction {
            for (p in posts) {
                exec(
                    "INSERT INTO index_posts (chat_id, message_id, unique_id, title, artist, duration_sec, size, mime, file_name, date, album, thumb, loose_key, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(chat_id, message_id) DO UPDATE SET " +
                        "unique_id = excluded.unique_id, title = excluded.title, artist = excluded.artist, duration_sec = excluded.duration_sec, size = excluded.size, " +
                        "mime = excluded.mime, file_name = excluded.file_name, date = excluded.date, album = CASE WHEN excluded.album = '' THEN album ELSE excluded.album END, " +
                        "thumb = COALESCE(excluded.thumb, thumb), loose_key = excluded.loose_key, updated_at = excluded.updated_at, src = '' " +
                        "WHERE unique_id IS NOT excluded.unique_id OR title IS NOT excluded.title OR artist IS NOT excluded.artist OR size IS NOT excluded.size " +
                        "OR (excluded.album <> '' AND album IS NOT excluded.album) OR (thumb IS NULL AND excluded.thumb IS NOT NULL)", 14,
                ) {
                    bindLong(0, p.chatId); bindLong(1, p.messageId); bindString(2, p.uniqueId); bindString(3, p.title); bindString(4, p.artist)
                    bindLong(5, p.durationSec.toLong()); bindLong(6, p.size); bindString(7, p.mime); bindString(8, p.fileName); bindLong(9, p.date)
                    bindString(10, p.album); bindBytes(11, p.thumb); bindString(12, looseTitleKey(p.title, p.artist).ifEmpty { "-" }); bindLong(13, at)
                }
                changed += query("SELECT changes()") { it.getLong(0)!!.toInt() }.first()
            }
            for (chat in posts.map { it.chatId }.toSet()) recount(chat)
        }
        touch()
        return changed
    }

    private fun recount(chat: Long) =
        exec("UPDATE index_chats SET post_count = (SELECT COUNT(*) FROM index_posts WHERE chat_id = ?) WHERE chat_id = ?", 2) { bindLong(0, chat); bindLong(1, chat) }

    private val postColumns = "chat_id, message_id, unique_id, title, artist, duration_sec, size, mime, file_name, date, album"

    private fun post(c: SqlCursor) = ChatPost(
        c.getLong(0)!!, c.getLong(1)!!, c.getString(2)!!, c.getString(3)!!, c.getString(4)!!,
        c.getLong(5)!!.toInt(), c.getLong(6)!!, c.getString(7)!!, c.getString(8)!!, c.getLong(9)!!, c.getString(10)!!,
    )

    fun posts(chatId: Long): List<ChatPost> =
        query("SELECT $postColumns FROM index_posts WHERE chat_id = ? ORDER BY message_id DESC", 1, { bindLong(0, chatId) }, ::post)

    fun postsByIds(chatId: Long, ids: Collection<Long>): List<ChatPost> = ids.chunked(500).flatMap { part ->
        query("SELECT $postColumns FROM index_posts WHERE chat_id = ? AND message_id IN (${part.joinToString(",")}) ORDER BY message_id DESC", 1, { bindLong(0, chatId) }, ::post)
    }

    /** Posts with the loose title key [key], for matching one wanted track; uses the key index, never the whole table. */
    fun postsWithKey(key: String): List<ChatPost> = query("SELECT $postColumns FROM index_posts WHERE loose_key = ?", 1, { bindString(0, key) }, ::post)

    /** Calls [each] for every post, in pages, so an export never holds them all at once. */
    fun forEachPost(chatIds: Set<Long>? = null, since: Long = 0, each: (ChatPost) -> Unit) {
        var lastChat = Long.MIN_VALUE
        var lastMsg = Long.MIN_VALUE
        while (true) {
            val page = query(
                "SELECT $postColumns FROM index_posts WHERE updated_at >= ? AND (chat_id > ? OR (chat_id = ? AND message_id > ?)) ORDER BY chat_id, message_id LIMIT 2000", 4,
                { bindLong(0, since); bindLong(1, lastChat); bindLong(2, lastChat); bindLong(3, lastMsg) }, ::post,
            )
            if (page.isEmpty()) return
            for (p in page) if (chatIds == null || p.chatId in chatIds) each(p)
            lastChat = page.last().chatId
            lastMsg = page.last().messageId
        }
    }

    /**
     * Posts changed after the cursor ([at], [chat], [msg]) in change order, for a linked device; posts that came from [exclude] are
     * left out. Each comes with its change time, which is the next cursor.
     */
    fun changedAfter(at: Long, chat: Long, msg: Long, exclude: String, limit: Int): List<Pair<ChatPost, Long>> = query(
        "SELECT $postColumns, updated_at FROM index_posts WHERE (updated_at > ?1 OR (updated_at = ?1 AND (chat_id > ?2 OR (chat_id = ?2 AND message_id > ?3)))) " +
            "AND src <> ?4 ORDER BY updated_at, chat_id, message_id LIMIT $limit", 4,
        { bindLong(0, at); bindLong(1, chat); bindLong(2, msg); bindString(3, exclude) },
    ) { post(it) to it.getLong(11)!! }

    /** Saves posts from a linked device with their own change times; a post changed here later than there is kept. */
    fun upsertSynced(posts: List<Pair<ChatPost, Long>>, src: String): Int {
        if (posts.isEmpty()) return 0
        var changed = 0
        db.transaction {
            for ((p, at) in posts) {
                exec(
                    "INSERT INTO index_posts (chat_id, message_id, unique_id, title, artist, duration_sec, size, mime, file_name, date, album, loose_key, updated_at, src) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(chat_id, message_id) DO UPDATE SET " +
                        "unique_id = excluded.unique_id, title = excluded.title, artist = excluded.artist, duration_sec = excluded.duration_sec, size = excluded.size, " +
                        "mime = excluded.mime, file_name = excluded.file_name, date = excluded.date, album = CASE WHEN excluded.album = '' THEN album ELSE excluded.album END, " +
                        "loose_key = excluded.loose_key, updated_at = excluded.updated_at, src = excluded.src " +
                        "WHERE excluded.updated_at > updated_at AND (unique_id IS NOT excluded.unique_id OR title IS NOT excluded.title OR artist IS NOT excluded.artist " +
                        "OR size IS NOT excluded.size OR (excluded.album <> '' AND album IS NOT excluded.album))", 14,
                ) {
                    bindLong(0, p.chatId); bindLong(1, p.messageId); bindString(2, p.uniqueId); bindString(3, p.title); bindString(4, p.artist)
                    bindLong(5, p.durationSec.toLong()); bindLong(6, p.size); bindString(7, p.mime); bindString(8, p.fileName); bindLong(9, p.date)
                    bindString(10, p.album); bindString(11, looseTitleKey(p.title, p.artist).ifEmpty { "-" }); bindLong(12, at); bindString(13, src)
                }
                changed += query("SELECT changes()") { it.getLong(0)!!.toInt() }.first()
            }
            for (chat in posts.map { it.first.chatId }.toSet()) recount(chat)
        }
        if (changed > 0) touch()
        return changed
    }

    /** True when the library has [chatId]/[messageId] (flags) or it was copied before. */
    fun had(chatId: Long, messageId: Long, uniqueId: String): Boolean = query(
        "SELECT (SELECT in_library FROM index_posts WHERE chat_id = ? AND message_id = ?) > 0 OR EXISTS (SELECT 1 FROM index_copied WHERE unique_id = ?)", 3,
        { bindLong(0, chatId); bindLong(1, messageId); bindString(2, uniqueId) },
    ) { it.getLong(0) == 1L }.firstOrNull() == true

    /** Message ids saved for [chatId]. */
    fun messageIds(chatId: Long): Set<Long> = query("SELECT message_id FROM index_posts WHERE chat_id = ?", 1, { bindLong(0, chatId) }) { it.getLong(0)!! }.toHashSet()

    /** Which of [uniqueIds] are posted in [chatId] (as of its last read). */
    fun posted(chatId: Long, uniqueIds: Collection<String>): Set<String> = uniqueIds.chunked(500).flatMapTo(HashSet()) { part ->
        query(
            "SELECT DISTINCT unique_id FROM index_posts WHERE chat_id = ? AND unique_id IN (${part.joinToString(",") { "?" }})", part.size + 1,
            { bindLong(0, chatId); part.forEachIndexed { i, u -> bindString(i + 1, u) } },
        ) { it.getString(0)!! }
    }

    /** The cover picture Telegram keeps inside the post (a tiny JPEG), if the file had one. */
    fun thumb(chatId: Long, messageId: Long): ByteArray? =
        query("SELECT thumb FROM index_posts WHERE chat_id = ? AND message_id = ?", 2, { bindLong(0, chatId); bindLong(1, messageId) }) { it.getBytes(0) }.firstOrNull()

    /** The oldest post saved for [chatId], or 0 when none is. */
    fun oldestMessageId(chatId: Long): Long =
        query("SELECT COALESCE(MIN(message_id), 0) FROM index_posts WHERE chat_id = ?", 1, { bindLong(0, chatId) }) { it.getLong(0)!! }.first()

    fun count(chatId: Long): Int = chat(chatId)?.postCount ?: 0

    /** The newest change time of any post, for "what changed since" questions from the other device. */
    fun lastUpdate(): Long = query("SELECT COALESCE(MAX(updated_at), 0) FROM index_posts") { it.getLong(0)!! }.first()

    fun deleteChat(id: Long) {
        db.transaction {
            exec("DELETE FROM index_posts WHERE chat_id = ?", 1) { bindLong(0, id) }
            exec("DELETE FROM index_chats WHERE chat_id = ?", 1) { bindLong(0, id) }
        }
        touch()
    }

    fun markCopied(p: ChatPost, at: Long) = markCopied(p.uniqueId, p.chatId, p.messageId, at)

    fun markCopied(uniqueId: String, chatId: Long, messageId: Long, at: Long) {
        exec("INSERT INTO index_copied (unique_id, chat_id, message_id, copied_at) VALUES (?, ?, ?, ?) ON CONFLICT(unique_id) DO UPDATE SET copied_at = MAX(copied_at, excluded.copied_at)", 4) {
            bindString(0, uniqueId); bindLong(1, chatId); bindLong(2, messageId); bindLong(3, at)
        }
        touch()
    }

    fun clearCopied() {
        exec("DELETE FROM index_copied")
        touch()
    }

    /** Unique ids of songs copied to the main chat from here. */
    fun copiedIds(): Set<String> = query("SELECT unique_id FROM index_copied") { it.getString(0)!! }.toSet()

    data class Copied(val uniqueId: String, val chatId: Long, val messageId: Long, val at: Long)

    fun copiedRows(): List<Copied> = query("SELECT unique_id, chat_id, message_id, copied_at FROM index_copied") {
        Copied(it.getString(0)!!, it.getLong(1)!!, it.getLong(2)!!, it.getLong(3)!!)
    }

    // ---- flags and pages ------------------------------------------------------------------------------------------------------------

    /**
     * Sets the library flags of every post. [libraryIds] are unique ids the library has as real posts; [libraryKeys] the loose keys of its
     * songs; [judge] decides, for a post whose key the library has, whether it is the same song (1 same file is decided by id) and why it
     * would be better (null when it is not); [wantedKeys] are keys Spotify playlists still wait for. Runs as a few set-based updates plus
     * one pass over the posts that share a key with the library.
     */
    fun refreshFlags(libraryIds: Set<String>, libraryKeys: Set<String>, wantedKeys: Set<String>, judge: (ChatPost) -> Pair<Boolean, String?>) {
        db.transaction {
            exec("CREATE TEMP TABLE IF NOT EXISTS idx_ids (v TEXT PRIMARY KEY)")
            exec("CREATE TEMP TABLE IF NOT EXISTS idx_keys (v TEXT PRIMARY KEY)")
            exec("CREATE TEMP TABLE IF NOT EXISTS idx_wanted (v TEXT PRIMARY KEY)")
            exec("DELETE FROM idx_ids"); exec("DELETE FROM idx_keys"); exec("DELETE FROM idx_wanted")
            for (v in libraryIds) exec("INSERT OR IGNORE INTO idx_ids VALUES (?)", 1) { bindString(0, v) }
            for (v in libraryKeys) exec("INSERT OR IGNORE INTO idx_keys VALUES (?)", 1) { bindString(0, v) }
            for (v in wantedKeys) exec("INSERT OR IGNORE INTO idx_wanted VALUES (?)", 1) { bindString(0, v) }
            // Only rows whose flags change are written: on a chat of 250,000 posts a full rewrite would cost seconds and tens of MB of
            // disk writes every time the library changes.
            exec("UPDATE index_posts SET in_library = 1, better = NULL WHERE unique_id IN (SELECT v FROM idx_ids) AND (in_library <> 1 OR better IS NOT NULL)")
            exec("UPDATE index_posts SET in_library = 0, better = NULL WHERE in_library <> 0 AND unique_id NOT IN (SELECT v FROM idx_ids) AND loose_key NOT IN (SELECT v FROM idx_keys)")
            exec("UPDATE index_posts SET wanted = 1 WHERE wanted = 0 AND loose_key IN (SELECT v FROM idx_wanted)")
            exec("UPDATE index_posts SET wanted = 0 WHERE wanted = 1 AND loose_key NOT IN (SELECT v FROM idx_wanted)")
            // Posts sharing a title with a library song are judged one by one (a few thousand at most).
            val sameKey = query(
                "SELECT $postColumns, in_library, better FROM index_posts WHERE unique_id NOT IN (SELECT v FROM idx_ids) AND loose_key IN (SELECT v FROM idx_keys)",
            ) { c -> Triple(post(c), c.getLong(11)!!, c.getString(12)) }
            for ((p, was, wasBetter) in sameKey) {
                val (same, better) = judge(p)
                val now = if (same) 2L else 0L
                val why = if (same) better else null
                if (now == was && why == wasBetter) continue
                exec("UPDATE index_posts SET in_library = ?, better = ? WHERE chat_id = ? AND message_id = ?", 4) {
                    bindLong(0, now); bindString(1, why); bindLong(2, p.chatId); bindLong(3, p.messageId)
                }
            }
        }
        touch()
    }

    private fun where(filter: IndexFilter): String = when (filter) {
        IndexFilter.All -> "1"
        IndexFilter.New -> "in_library = 0 AND unique_id NOT IN (SELECT unique_id FROM index_copied)"
        IndexFilter.Better -> "better IS NOT NULL"
        IndexFilter.Have -> "in_library > 0"
        IndexFilter.Wanted -> "wanted = 1 AND in_library = 0 AND unique_id NOT IN (SELECT unique_id FROM index_copied)"
    }

    private fun search(q: String) = if (q.isBlank()) "" else " AND (title LIKE ?2 OR artist LIKE ?2 OR file_name LIKE ?2 OR album LIKE ?2)"

    private fun like(q: String) = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** One page of a chat's posts, newest first, with their flags; [query] matches title, artist, album or file name. */
    fun page(chatId: Long, filter: IndexFilter, query: String, offset: Int, limit: Int, mainChat: Long?): List<IndexedSong> {
        val sql = "SELECT $postColumns, in_library, better, wanted, unique_id IN (SELECT unique_id FROM index_copied) FROM index_posts " +
            "WHERE chat_id = ?1 AND ${where(filter)}${search(query).replace("LIKE ?2", "LIKE ?2 ESCAPE '\\'")} ORDER BY message_id DESC LIMIT $limit OFFSET $offset"
        return query(sql, if (query.isBlank()) 1 else 2, { bindLong(0, chatId); if (query.isNotBlank()) bindString(1, like(query)) }) { c ->
            val p = post(c)
            val inLib = c.getLong(11)!! > 0
            val better = c.getString(12)
            IndexedSong(p, inMainChat = inLib, copied = c.getLong(14) == 1L, better = better != null, betterWhy = better, wanted = c.getLong(13) == 1L, ownChat = p.chatId == mainChat)
        }
    }

    /** The posts a filter and search select, for "add all shown". */
    fun select(chatId: Long, filter: IndexFilter, query: String): List<ChatPost> {
        val sql = "SELECT $postColumns FROM index_posts WHERE chat_id = ?1 AND ${where(filter)}${search(query).replace("LIKE ?2", "LIKE ?2 ESCAPE '\\'")} ORDER BY message_id DESC"
        return query(sql, if (query.isBlank()) 1 else 2, { bindLong(0, chatId); if (query.isNotBlank()) bindString(1, like(query)) }, ::post)
    }

    /** How many posts each filter shows for a chat and search, in [IndexFilter] order. */
    fun counts(chatId: Long, query: String): List<Int> {
        val copied = "unique_id IN (SELECT unique_id FROM index_copied)"
        val sql = "SELECT COUNT(*), SUM(in_library = 0 AND NOT $copied), SUM(better IS NOT NULL), SUM(in_library > 0), SUM(wanted = 1 AND in_library = 0 AND NOT $copied) " +
            "FROM index_posts WHERE chat_id = ?1${search(query).replace("LIKE ?2", "LIKE ?2 ESCAPE '\\'")}"
        return query(sql, if (query.isBlank()) 1 else 2, { bindLong(0, chatId); if (query.isNotBlank()) bindString(1, like(query)) }) { c ->
            List(5) { (c.getLong(it) ?: 0L).toInt() }
        }.first()
    }

    /** Posts of any chat whose title, artist or album match [query], for the search screen. */
    fun searchAll(query: String, limit: Int): List<ChatPost> {
        if (query.isBlank()) return emptyList()
        return query(
            "SELECT $postColumns FROM index_posts WHERE title LIKE ?1 ESCAPE '\\' OR artist LIKE ?1 ESCAPE '\\' OR album LIKE ?1 ESCAPE '\\' ORDER BY in_library, date DESC LIMIT $limit", 1,
            { bindString(0, like(query)) }, ::post,
        )
    }
}
