package io.max.paperplay.core.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The index as a file: what the account's cloud copy, an export to share and a restore all use.
 *
 * ```
 * {"version":2, "app":"paperplay-index", "createdAt":ms,
 *  "chats":[{"id", "title", "username", "link", "channel", "newest", "refreshed", "complete", "count"}],
 *  "posts":[[chat, message, uniqueId, title, artist, seconds, bytes, mime, fileName, date, album]],
 *  "copied":[[uniqueId, chat, message, at]]}
 * ```
 * Version 1 files (posts without the album, chats without links) are read the same way. Message ids are TDLib's, which are the same
 * for every member of a chat, so a file made by someone else works once this account has joined the chat.
 */
object IndexFile {
    /**
     * Writes [chatIds] (all when null). [forSharing] leaves out chats nobody else could join (no public name and no invite link) and the
     * copied marks, which only mean something to this library. Posts are written a page at a time, never all held at once.
     */
    fun write(store: IndexStore, out: Appendable, chatIds: Set<Long>?, forSharing: Boolean, now: Long) {
        val chats = store.chats().filter { (chatIds == null || it.id in chatIds) && (!forSharing || it.publicLink != null) }
        fun str(s: String) = JsonPrimitive(s).toString()
        out.append("{\"version\":2,\"app\":\"paperplay-index\",\"createdAt\":").append(now.toString()).append(",\"chats\":[")
        chats.forEachIndexed { i, c ->
            if (i > 0) out.append(',')
            out.append("{\"id\":").append(c.id.toString()).append(",\"title\":").append(str(c.title))
                .append(",\"username\":").append(str(c.username)).append(",\"link\":").append(str(c.publicLink ?: ""))
                .append(",\"channel\":").append(c.isChannel.toString()).append(",\"newest\":").append(c.newestMessageId.toString())
                .append(",\"refreshed\":").append(c.lastRefreshAt.toString()).append(",\"complete\":").append(c.complete.toString())
                .append(",\"count\":").append(c.postCount.toString()).append('}')
        }
        out.append("],\"posts\":[")
        var first = true
        store.forEachPost(chats.map { it.id }.toSet()) { p ->
            if (!first) out.append(',')
            first = false
            out.append('[').append(p.chatId.toString()).append(',').append(p.messageId.toString()).append(',').append(str(p.uniqueId))
                .append(',').append(str(p.title)).append(',').append(str(p.artist)).append(',').append(p.durationSec.toString())
                .append(',').append(p.size.toString()).append(',').append(str(p.mime)).append(',').append(str(p.fileName))
                .append(',').append(p.date.toString()).append(',').append(str(p.album)).append(']')
        }
        out.append("],\"copied\":[")
        if (!forSharing) store.copiedRows().forEachIndexed { i, c ->
            if (i > 0) out.append(',')
            out.append('[').append(str(c.uniqueId)).append(',').append(c.chatId.toString()).append(',').append(c.messageId.toString())
                .append(',').append(c.at.toString()).append(']')
        }
        out.append("]}")
    }

    /**
     * Merges a file into [store]. Nothing is deleted; for a chat already saved the further read and a finished one win. [own] is true for
     * this account's own copy (its copied marks are taken, every chat counts as joined); otherwise new chats are saved as not joined and
     * the caller checks them. Returns the ids of the new chats in [newChatIds]. Throws [IllegalArgumentException] for a file that is not
     * an index.
     */
    fun read(text: String, store: IndexStore, now: Long, own: Boolean, newChatIds: MutableList<Long>? = null): IndexImport =
        read(JsonStream(java.io.StringReader(text)), store, now, own, newChatIds)

    /** The same, read piece by piece: a big index never sits in memory. */
    fun read(json: JsonStream, store: IndexStore, now: Long, own: Boolean, newChatIds: MutableList<Long>? = null): IndexImport {
        if (!json.isObject) throw IllegalArgumentException("This is not a PaperPlay index file.")
        var chats = JsonArray(emptyList())
        var counts = 0 to 0
        var isIndex = false
        var posts = 0
        val batch = ArrayList<ChatPost>(2000)
        fun flush() { if (batch.isNotEmpty()) { posts += store.upsertPosts(batch, now); batch.clear() } }
        json.obj { key ->
            when {
                key == "chats" && json.isArray -> {
                    isIndex = true
                    chats = json.value() as JsonArray
                    counts = mergeChats(chats, store, now, own, newChatIds, progress = false)
                }
                key == "posts" && json.isArray -> {
                    isIndex = true
                    json.arr {
                        val a = json.value() as? JsonArray ?: return@arr
                        batch += runCatching {
                            fun s(i: Int) = (a[i] as JsonPrimitive).content
                            fun n(i: Int) = s(i).toLong()
                            ChatPost(n(0), n(1), s(2), s(3), s(4), n(5).toInt(), n(6), s(7), s(8), n(9), if (a.size > 10) s(10) else "")
                        }.getOrNull() ?: return@arr
                        if (batch.size >= 2000) flush()
                    }
                    flush()
                }
                key == "copied" && own && json.isArray -> json.arr {
                    val a = json.value() as? JsonArray ?: return@arr
                    runCatching {
                        store.markCopied((a[0] as JsonPrimitive).content, (a[1] as JsonPrimitive).content.toLong(), (a[2] as JsonPrimitive).content.toLong(), (a[3] as JsonPrimitive).content.toLong())
                    }
                }
                else -> json.skip()
            }
        }
        if (!isIndex) throw IllegalArgumentException("This is not a PaperPlay index file.")
        mergeChats(chats, store, now, own, progress = true)
        return IndexImport(counts.first, posts, counts.second)
    }

    /**
     * Merges chat entries (the "chats" of a file) into [store]: new chats are added, known ones keep the further read, the finished
     * state and any link either side knows. Returns (new chats, new chats not joined). Call it with [progress] false before the posts and
     * with true after the last one: a chat that counts as read further than its stored posts makes the missing ones look deleted in
     * Telegram ([ChatIndex.pruneDeadRows] would remove their songs).
     */
    fun mergeChats(chats: JsonArray, store: IndexStore, now: Long, own: Boolean, newChatIds: MutableList<Long>? = null, progress: Boolean): Pair<Int, Int> {
        fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } == true
        val mine = store.chats().associateBy { it.id }
        var newChats = 0
        var notJoined = 0
        for (e in chats) {
            val o = e as? JsonObject ?: continue
            val id = o.l("id") ?: continue
            val title = o.s("title") ?: continue
            val had = mine[id]
            store.saveChat(id, title, now)
            val link = o.s("link").orEmpty()
            val username = o.s("username").orEmpty().ifEmpty { link.removePrefix("https://t.me/").takeIf { link.startsWith("https://t.me/") && '/' !in it && '+' !in it }.orEmpty() }
            val invite = if (link.isNotEmpty() && username.isEmpty()) link else ""
            val joined = had?.joined ?: own
            if (had == null) { newChats++; newChatIds?.add(id); if (!joined) notJoined++ }
            store.setChatInfo(id, username, invite, o["channel"]?.let { o.b("channel") } ?: true, joined, now)
            if (!joined) store.setJoined(id, false)
            if (progress) {
                val now2 = store.chat(id) ?: continue
                // Fewer posts here than the other side has: some did not come along, so its "read in full" does not hold here.
                // An older build does not say how many it has.
                if (now2.postCount < (o.l("count") ?: continue)) continue
                val newest = o.l("newest") ?: 0L
                val complete = o.b("complete")
                store.setState(id, 0L, o.l("refreshed") ?: 0L, now2.complete)
                // "Complete" means nothing is missing up to its own newest post; the newest post of a read that never finished says nothing.
                when {
                    complete && now2.complete -> store.setRead(id, maxOf(newest, now2.newestMessageId), true)
                    complete -> store.setRead(id, newest, true)
                }
            }
        }
        return newChats to notJoined
    }
}
