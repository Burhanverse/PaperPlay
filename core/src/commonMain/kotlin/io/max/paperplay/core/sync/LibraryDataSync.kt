package io.max.paperplay.core.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.IndexFile
import io.max.paperplay.core.account.IndexStore
import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.filing.GONE_KEY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.withLock

/**
 * Makes two linked devices hold the same database: library songs one of them lacks, and the whole chat index.
 *
 * - Songs: the other device lists its songs by unique id; the ones missing here (and not deleted here) are fetched as full rows, 500 at a
 *   time, without this device's cover file path (covers follow through the normal cover exchange).
 * - Index: posts are fetched in change order from a cursor kept per device, 2,000 at a time, so a first sync of 250,000 posts resumes
 *   where it stopped and later syncs send only what changed. A post fetched from a device is never sent back to it.
 * - Pause: the later of the two pause or resume decisions wins.
 */
class LibraryDataSync(
    private val driver: SqlDriver,
    private val library: LibraryRepository,
    private val index: () -> IndexStore?,
    /** (paused, when it was decided). */
    private val pauseState: () -> Pair<Boolean, Long>,
    /** Takes the other device's pause decision when it is the later one. */
    private val takePause: (paused: Boolean, at: Long) -> Unit,
    /** Called after posts or chats arrived, so the index lists refresh. */
    private val onIndexChanged: () -> Unit = {},
    /** The main chat: the only chat where deleting a song deletes its post in Telegram. */
    private val mainChat: () -> Long? = { null },
) : LanDataSync {

    override fun answer(peerId: String, request: JsonObject): JsonObject = when ((request["k"] as? JsonPrimitive)?.contentOrNull) {
        "songkeys" -> buildJsonObject { put("ids", buildJsonArray { for (id in songIds()) add(JsonPrimitive(id)) }) }
        "songrows" -> buildJsonObject {
            val ids = (request["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(MAX_ROWS)
            put("rows", songRows(ids))
        }
        "index" -> indexPage(peerId, request)
        else -> throw IllegalArgumentException("unknown request")
    }

    private val pulling = kotlinx.coroutines.sync.Mutex()

    // One pull at a time: a nudge and the timed sync can overlap, and two pulls would race on the same cursor.
    override suspend fun pull(peerId: String, ask: suspend (JsonObject) -> JsonObject): Int = pulling.withLock {
        val added = pullSongs(ask)
        pullIndex(peerId, ask)
        added
    }

    // ---- songs ----------------------------------------------------------------------------------------------------------------------

    private fun songIds(): List<String> = driver.executeQuery(null, "SELECT unique_id FROM songs", { c ->
        val out = ArrayList<String>()
        while (c.next().value) out += c.getString(0)!!
        QueryResult.Value<List<String>>(out)
    }, 0).value

    private fun columns(): List<Pair<String, String>> = driver.executeQuery(null, "PRAGMA table_info(songs)", { c ->
        val out = ArrayList<Pair<String, String>>()
        while (c.next().value) out += c.getString(1)!! to c.getString(2)!!.uppercase()
        QueryResult.Value<List<Pair<String, String>>>(out)
    }, 0).value

    private fun songRows(ids: List<String>): JsonArray {
        if (ids.isEmpty()) return JsonArray(emptyList())
        val cols = columns().filter { it.first !in LOCAL_ONLY }
        val names = cols.joinToString(",") { "\"${it.first}\"" }
        return driver.executeQuery(null, "SELECT $names FROM songs WHERE unique_id IN (${ids.joinToString(",") { "?" }})", { c ->
            val rows = ArrayList<JsonObject>()
            while (c.next().value) rows += JsonObject(cols.mapIndexed { i, (name, type) ->
                name to when {
                    type.startsWith("INT") -> c.getLong(i)?.let { JsonPrimitive(it) } ?: JsonNull
                    type.startsWith("REAL") -> c.getDouble(i)?.let { JsonPrimitive(it) } ?: JsonNull
                    else -> c.getString(i)?.let { JsonPrimitive(it) } ?: JsonNull
                }
            }.toMap())
            QueryResult.Value(JsonArray(rows))
        }, ids.size) { ids.forEachIndexed { i, id -> bindString(i, id) } }.value
    }

    /** Unique ids this device deleted on purpose: never brought back by a sync. */
    private fun deleted(): Set<String> =
        runCatching { Json.parseToJsonElement(library.pref(GONE_KEY) ?: "{}").jsonObject.keys }.getOrDefault(emptySet())

    override fun stillPosted(ids: Set<String>): Set<String> {
        val main = mainChat() ?: return emptySet()
        return index()?.posted(main, ids).orEmpty()
    }

    private fun forget(ids: Set<String>) {
        val o = runCatching { Json.parseToJsonElement(library.pref(GONE_KEY) ?: "{}").jsonObject }.getOrNull() ?: return
        library.setPref(GONE_KEY, JsonObject(o.filterKeys { it !in ids }).toString())
    }

    private suspend fun pullSongs(ask: suspend (JsonObject) -> JsonObject): Int {
        val theirs = (ask(buildJsonObject { put("k", "songkeys") })["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val mine = songIds().toHashSet()
        // Deleting a song deletes its post in the main chat. A deletion entry whose post is still in the main chat (by the index's last
        // read) is stale: the song comes back, and the entry leaves the record so the next sync does not remove it again. Posts in
        // other chats are never deleted, so a song deleted here that is also posted there stays deleted.
        val gone = deleted()
        val stale = stillPosted(theirs.filterTo(HashSet()) { it !in mine && it in gone })
        if (stale.isNotEmpty()) forget(stale)
        val missing = theirs.filter { it !in mine && (it !in gone || it in stale) }
        if (missing.isEmpty()) return 0
        val cols = columns().associate { it.first to it.second }
        var added = 0
        for (part in missing.chunked(MAX_ROWS)) {
            val rows = (ask(buildJsonObject { put("k", "songrows"); put("ids", buildJsonArray { part.forEach { add(JsonPrimitive(it)) } }) })["rows"] as? JsonArray).orEmpty()
            library.batch(soft = false) {
                for (r in rows) {
                    val o = r as? JsonObject ?: continue
                    val entries = o.entries.filter { (k, v) -> k in cols && k !in LOCAL_ONLY && v !is JsonNull }
                    if (entries.isEmpty()) continue
                    driver.execute(
                        null,
                        "INSERT OR IGNORE INTO songs (${entries.joinToString(",") { "\"${it.key}\"" }}) VALUES (${entries.joinToString(",") { "?" }})",
                        entries.size,
                    ) {
                        entries.forEachIndexed { i, (k, v) ->
                            val p = v as JsonPrimitive
                            val type = cols.getValue(k)
                            when {
                                type.startsWith("INT") -> bindLong(i, p.longOrNull ?: (if (p.contentOrNull == "true") 1L else 0L))
                                type.startsWith("REAL") -> bindDouble(i, p.contentOrNull?.toDoubleOrNull())
                                else -> bindString(i, p.contentOrNull)
                            }
                        }
                    }
                    added++
                }
            }
        }
        return added
    }

    // ---- index ----------------------------------------------------------------------------------------------------------------------

    private fun chatsJson(store: IndexStore) = buildJsonArray {
        for (c in store.chats()) add(buildJsonObject {
            put("id", c.id); put("title", c.title); put("username", c.username); put("link", c.publicLink ?: ""); put("channel", c.isChannel)
            put("newest", c.newestMessageId); put("refreshed", c.lastRefreshAt); put("complete", c.complete)
        })
    }

    private fun indexPage(peerId: String, q: JsonObject): JsonObject {
        val store = index() ?: return buildJsonObject { put("none", true) }
        fun l(k: String) = (q[k] as? JsonPrimitive)?.longOrNull ?: 0L
        val page = store.changedAfter(l("at"), l("chat"), l("msg"), peerId, INDEX_PAGE)
        return buildJsonObject {
            if ((q["first"] as? JsonPrimitive)?.contentOrNull == "true") {
                put("chats", chatsJson(store))
                put("removed", buildJsonArray { for ((id, gone, at) in store.removedRows()) add(buildJsonArray { add(JsonPrimitive(id)); add(JsonPrimitive(gone)); add(JsonPrimitive(at)) }) })
                put("copied", buildJsonArray {
                    for (c in store.copiedRows()) add(buildJsonArray { add(JsonPrimitive(c.uniqueId)); add(JsonPrimitive(c.chatId)); add(JsonPrimitive(c.messageId)); add(JsonPrimitive(c.at)) })
                })
                val (paused, at) = pauseState()
                put("paused", paused); put("pausedAt", at)
            }
            put("posts", buildJsonArray {
                for ((p, at) in page) add(buildJsonArray {
                    add(JsonPrimitive(p.chatId)); add(JsonPrimitive(p.messageId)); add(JsonPrimitive(p.uniqueId)); add(JsonPrimitive(p.title))
                    add(JsonPrimitive(p.artist)); add(JsonPrimitive(p.durationSec)); add(JsonPrimitive(p.size)); add(JsonPrimitive(p.mime))
                    add(JsonPrimitive(p.fileName)); add(JsonPrimitive(p.date)); add(JsonPrimitive(p.album)); add(JsonPrimitive(at))
                })
            })
            put("more", page.size == INDEX_PAGE)
        }
    }

    private suspend fun pullIndex(peerId: String, ask: suspend (JsonObject) -> JsonObject) {
        val store = index() ?: return
        val key = "index_sync:$peerId"
        // The cursor is (change time, chat, message) of the last post taken from this device; -1 takes everything.
        val saved = library.pref(key)?.split(':')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 } ?: listOf(-1L, 0L, 0L)
        var at = saved[0]; var chat = saved[1]; var msg = saved[2]
        var first = true
        while (true) {
            val a = ask(buildJsonObject { put("k", "index"); put("at", at); put("chat", chat); put("msg", msg); put("first", first) })
            if (a["none"] != null) return
            if (first) {
                // Removed chats first, so a chat the owner removed on either device is not added back by the list below.
                val removed = (a["removed"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val r = e as? JsonArray ?: return@mapNotNull null
                    runCatching { Triple((r[0] as JsonPrimitive).content.toLong(), (r[1] as JsonPrimitive).content == "true", (r[2] as JsonPrimitive).content.toLong()) }.getOrNull()
                }
                store.mergeRemoved(removed)
                (a["chats"] as? JsonArray)?.let { chats -> IndexFile.mergeChats(JsonArray(chats.filterNot { c -> ((c as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull?.let(store::isGone) == true }), store, 0, own = true) }
                for (e in (a["copied"] as? JsonArray).orEmpty()) {
                    val c = e as? JsonArray ?: continue
                    runCatching { store.markCopied((c[0] as JsonPrimitive).content, (c[1] as JsonPrimitive).content.toLong(), (c[2] as JsonPrimitive).content.toLong(), (c[3] as JsonPrimitive).content.toLong()) }
                }
                val theirsAt = (a["pausedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (theirsAt > pauseState().second) takePause((a["paused"] as? JsonPrimitive)?.contentOrNull == "true", theirsAt)
                first = false
            }
            val posts = (a["posts"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonArray ?: return@mapNotNull null
                runCatching {
                    fun s(i: Int) = (p[i] as JsonPrimitive).content
                    fun n(i: Int) = s(i).toLong()
                    ChatPost(n(0), n(1), s(2), s(3), s(4), n(5).toInt(), n(6), s(7), s(8), n(9), s(10)) to n(11)
                }.getOrNull()
            }
            if (posts.isNotEmpty()) {
                store.upsertSynced(posts.filterNot { store.isGone(it.first.chatId) }, peerId)
                val last = posts.last()
                at = last.second; chat = last.first.chatId; msg = last.first.messageId
                library.setPref(key, "$at:$chat:$msg")
            }
            onIndexChanged()
            if ((a["more"] as? JsonPrimitive)?.contentOrNull != "true" || posts.isEmpty()) return
        }
    }

    private companion object {
        const val MAX_ROWS = 500
        const val INDEX_PAGE = 2000
        /** Columns that only mean something on this device: a cover file path and how much of the song is cached here. */
        val LOCAL_ONLY = setOf("cover_path", "cached_bytes")
    }
}
