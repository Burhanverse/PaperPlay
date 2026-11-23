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
    fun read(text: String, store: IndexStore, now: Long, own: Boolean, newChatIds: MutableList<Long>? = null): IndexImport {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("This is not a PaperPlay index file.")
        if (root["posts"] !is JsonArray && root["chats"] !is JsonArray) throw IllegalArgumentException("This is not a PaperPlay index file.")
        fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } == true
        val mine = store.chats().associateBy { it.id }
        var newChats = 0
        var notJoined = 0
        for (e in root["chats"] as? JsonArray ?: JsonArray(emptyList())) {
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
            store.setState(id, maxOf(o.l("newest") ?: 0L, had?.newestMessageId ?: 0L), maxOf(o.l("refreshed") ?: 0L, had?.lastRefreshAt ?: 0L), o.b("complete") || had?.complete == true)
        }
        var posts = 0
        val batch = ArrayList<ChatPost>(2000)
        fun flush() { if (batch.isNotEmpty()) { posts += store.upsertPosts(batch, now); batch.clear() } }
        for (e in root["posts"] as? JsonArray ?: JsonArray(emptyList())) {
            val a = e as? JsonArray ?: continue
            val p = runCatching {
                fun s(i: Int) = (a[i] as JsonPrimitive).content
                fun n(i: Int) = s(i).toLong()
                ChatPost(n(0), n(1), s(2), s(3), s(4), n(5).toInt(), n(6), s(7), s(8), n(9), if (a.size > 10) s(10) else "")
            }.getOrNull() ?: continue
            batch += p
            if (batch.size >= 2000) flush()
        }
        flush()
        if (own) for (e in root["copied"] as? JsonArray ?: JsonArray(emptyList())) {
            val a = e as? JsonArray ?: continue
            runCatching {
                store.markCopied((a[0] as JsonPrimitive).content, (a[1] as JsonPrimitive).content.toLong(), (a[2] as JsonPrimitive).content.toLong(), (a[3] as JsonPrimitive).content.toLong())
            }
        }
        return IndexImport(newChats, posts, notJoined)
    }
}
