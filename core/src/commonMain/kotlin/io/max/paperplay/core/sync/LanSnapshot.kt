package io.max.paperplay.core.sync

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.filing.GONE_KEY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.max.paperplay.core.library.legacyPlaylistUuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tags that travel with an edited song, plus the pin. */
data class SongExtra(
    val artist: String, val album: String, val genre: String?, val year: Long?, val pinned: Boolean,
    val lyrics: String? = null, val releaseId: String? = null, val coverUrl: String? = null, val metadataSource: String? = null,
    /** Song info was found (by a lookup or by hand). */
    val enriched: Boolean = false,
    /** When the cover was chosen by hand, 0 when it is an automatic one. The cover file itself travels in its own message. */
    val coverAt: Long = 0,
    /** This device has a cover file for the song (chosen by hand or found automatically). */
    val hasCover: Boolean = false,
)

/**
 * The song related data of one device: [state] feeds [mergeLibrary], [extra] holds the tag fields of edited songs,
 * [order] the live playlist order (unique ids) by playlist uuid. Never holds tokens, session or secrets.
 */
data class Snapshot(
    val state: LibraryState,
    val extra: Map<String, SongExtra> = emptyMap(),
    val order: Map<String, List<String>> = emptyMap(),
)

/** What an apply changed. */
data class ApplyResult(val songsChanged: Int, val playlistsChanged: Int, val wantCovers: List<String> = emptyList())

/** Wire form of a [Snapshot]. */
object SnapshotCodec {
    fun encode(s: Snapshot): JsonObject = JsonObject(
        mapOf(
            "v" to JsonPrimitive(1),
            "songs" to JsonArray(
                s.state.songs.map { (id, v) ->
                    val e = s.extra[id]
                    JsonObject(
                        buildMap<String, JsonElement> {
                            put("id", JsonPrimitive(id)); put("title", JsonPrimitive(v.title)); put("edited", JsonPrimitive(v.userEdited))
                            put("tagsAt", JsonPrimitive(v.tagsAt)); put("fav", JsonPrimitive(v.favorite)); put("favAt", JsonPrimitive(v.favoriteAt))
                            put("plays", JsonPrimitive(v.plays)); put("skips", JsonPrimitive(v.skips)); put("last", v.lastPlayedAt.orNull())
                            if (e != null) {
                                put("artist", JsonPrimitive(e.artist)); put("album", JsonPrimitive(e.album)); put("genre", e.genre.orNull())
                                put("year", e.year.orNull()); put("pinned", JsonPrimitive(e.pinned))
                                e.lyrics?.let { put("lyr", JsonPrimitive(it)) }; e.releaseId?.let { put("rel", JsonPrimitive(it)) }
                                e.coverUrl?.let { put("curl", JsonPrimitive(it)) }; e.metadataSource?.let { put("msrc", JsonPrimitive(it)) }
                                if (e.enriched) put("enr", JsonPrimitive(true))
                                if (e.coverAt != 0L) put("cat", JsonPrimitive(e.coverAt))
                                if (e.hasCover) put("hc", JsonPrimitive(true))
                            }
                        },
                    )
                },
            ),
            "gone" to JsonObject(s.state.gone.mapValues { JsonPrimitive(it.value) }),
            "playlists" to JsonArray(
                s.state.playlists.map { (uuid, p) ->
                    JsonObject(
                        mapOf(
                            "uuid" to JsonPrimitive(uuid), "name" to JsonPrimitive(p.name), "modifiedAt" to JsonPrimitive(p.modifiedAt),
                            "deletedAt" to p.deletedAt.orNull(),
                            "items" to JsonArray(
                                p.items.map { (id, i) ->
                                    JsonObject(mapOf("id" to JsonPrimitive(id), "addedAt" to JsonPrimitive(i.addedAt), "removedAt" to i.removedAt.orNull()))
                                },
                            ),
                            "order" to JsonArray((s.order[uuid] ?: emptyList()).map { JsonPrimitive(it) }),
                        ),
                    )
                },
            ),
        ),
    )

    /** Throws IllegalArgumentException on a damaged message; the caller drops the message. */
    fun decode(o: JsonObject): Snapshot {
        val songs = HashMap<String, SongSync>()
        val extra = HashMap<String, SongExtra>()
        for (e in o.reqArr("songs")) {
            val j = e as? JsonObject ?: throw IllegalArgumentException("bad song")
            val id = j.reqStr("id")
            songs[id] = SongSync(
                j.reqStr("title"), j.reqFlag("edited"), j.reqLng("tagsAt"), j.reqFlag("fav"), j.reqLng("favAt"),
                j.reqLng("plays"), j.reqLng("skips"), j.lng("last"),
            )
            if (j.str("artist") != null) {
                extra[id] = SongExtra(
                    j.reqStr("artist"), j.str("album") ?: "", j.str("genre"), j.lng("year"), j.flag("pinned") ?: false,
                    j.str("lyr"), j.str("rel"), j.str("curl"), j.str("msrc"), j.flag("enr") ?: false, j.lng("cat") ?: 0L, j.flag("hc") ?: false,
                )
            }
        }
        val gone = HashMap<String, Long>()
        o.obj("gone")?.forEach { (k, v) -> gone[k] = v.longAt() }
        val lists = HashMap<String, PlaylistSync>()
        val order = HashMap<String, List<String>>()
        for (e in o.reqArr("playlists")) {
            val j = e as? JsonObject ?: throw IllegalArgumentException("bad playlist")
            val uuid = j.reqStr("uuid")
            val items = HashMap<String, ItemSync>()
            for (it in j.reqArr("items")) {
                val ij = it as? JsonObject ?: throw IllegalArgumentException("bad item")
                items[ij.reqStr("id")] = ItemSync(ij.reqLng("addedAt"), ij.lng("removedAt"))
            }
            lists[uuid] = PlaylistSync(j.reqStr("name"), j.reqLng("modifiedAt"), j.lng("deletedAt"), items)
            order[uuid] = j.arr("order")?.map { it.stringAt() } ?: emptyList()
        }
        return Snapshot(LibraryState(songs, gone, lists), extra, order)
    }
}

private fun ItemSync.live() = removedAt == null || removedAt <= addedAt

private const val LYRICS_BUDGET = 8_000_000
private const val GONE_KEEP_MS = 365L * 24 * 3600 * 1000

/** Builds a snapshot from the database and applies a merged one back. Songs this device does not have are skipped. */
class SnapshotStore(private val library: LibraryRepository) {
    private val db get() = library.repo.db

    /**
     * @param onlyWithState true for what is sent: songs with a favorite, plays, skips, pin or edited tags. False for the
     * full local state, which [apply] needs to compare against.
     */
    fun build(onlyWithState: Boolean = true): Snapshot {
        val songs = HashMap<String, SongSync>()
        val extra = HashMap<String, SongExtra>()
        var lyricsBudget = LYRICS_BUDGET
        for (s in db.songsQueries.allSongs().executeAsList()) {
            val has = s.favorite || s.plays > 0 || s.skips > 0 || s.last_played_at != null || s.user_edited || s.pinned
            // Everything that was looked up or chosen travels too, so a device that lacks it can fill it in.
            val data = s.enrich_state == io.max.paperplay.core.library.EnrichState.DONE || !s.lyrics.isNullOrBlank() || s.cover_at != 0L || s.cover_path != null
            if (onlyWithState && !has && !data) continue
            songs[s.unique_id] = SongSync(s.title, s.user_edited, s.tags_at, s.favorite, s.favorite_at, s.plays, s.skips, s.last_played_at)
            // Lyrics are the bulky part, kept under a budget so a frame never grows past what a link takes.
            val lyrics = if (onlyWithState) s.lyrics?.takeIf { it.isNotBlank() && it.length <= 20_000 && it.length <= lyricsBudget }?.also { lyricsBudget -= it.length } else null
            extra[s.unique_id] = SongExtra(
                s.artist, s.album, s.genre, s.year, s.pinned, lyrics, s.release_id, s.cover_url, s.metadata_source,
                s.enrich_state == io.max.paperplay.core.library.EnrichState.DONE, s.cover_at, s.cover_path != null,
            )
        }
        for (p in db.playlistsQueries.emptyUuidPlaylists().executeAsList()) {
            db.playlistsQueries.setPlaylistUuid(legacyPlaylistUuid(p.id, p.name), p.id)
        }
        val entries = db.lanQueries.allEntries().executeAsList().groupBy { it.playlist_id }
        val lists = HashMap<String, PlaylistSync>()
        val order = HashMap<String, List<String>>()
        for (p in db.lanQueries.allPlaylistsWithUuid().executeAsList()) {
            val es = entries[p.id].orEmpty()
            lists[p.uuid] = PlaylistSync(
                p.name, p.modified_at, p.deleted_at, es.associate { it.unique_id to ItemSync(it.added_at, it.removed_at) },
            )
            order[p.uuid] = es.filter { it.removed_at == null }.sortedWith(compareBy({ it.position }, { it.added_at })).map { it.unique_id }
        }
        return Snapshot(LibraryState(songs, goneRecord(), lists), extra, order)
    }

    /** The deletion record kept by the delete queue: song unique ids and content keys, with when they were deleted. Old entries expire. */
    private fun goneRecord(): Map<String, Long> {
        val cutoff = System.currentTimeMillis() - GONE_KEEP_MS
        val o = runCatching { Json.parseToJsonElement(library.pref(GONE_KEY) ?: "{}").jsonObject }.getOrNull() ?: return emptyMap()
        return o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > cutoff }?.let { k to it } }.toMap()
    }

    /** Merges [remote] into the database (one transaction) and returns what changed. */
    fun apply(remote: Snapshot): ApplyResult {
        val local = build(onlyWithState = false)
        val merged = mergeLibrary(local.state, remote.state)
        var songsChanged = 0
        var playlistsChanged = 0
        val wantCovers = ArrayList<String>()
        library.batch(soft = false) {
            val rows = db.songsQueries.allSongs().executeAsList().associateBy { it.unique_id }
            val changed = ArrayList<io.max.paperplay.core.library.Song>()
            for ((id, m) in merged.songs) {
                val row = rows[id] ?: continue
                val l = local.state.songs[id]
                val r = remote.state.songs[id]
                var n = row.copy(
                    title = m.title, user_edited = m.userEdited, tags_at = m.tagsAt, favorite = m.favorite, favorite_at = m.favoriteAt,
                    plays = m.plays, skips = m.skips, last_played_at = m.lastPlayedAt,
                )
                val re = remote.extra[id]
                if (re != null) {
                    if (l != null && r != null) n = mergeSongData(n, l, r, re)
                    if (re.pinned && !n.pinned) n = n.copy(pinned = true)
                    // A cover the other device has and this one lacks, or a hand-chosen one that is newer: its file is asked for after the merge.
                    if (re.coverAt > row.cover_at || (re.hasCover && row.cover_path == null)) wantCovers += id
                }
                if (n != row) changed += n
            }
            library.replaceSongs(changed)
            songsChanged = changed.size

            // Songs deleted on the other device go here too, unless the file was posted again after the deletion. Content keys
            // ("song:...") only stop a song coming back; they would also match the copy that was kept, so they remove nothing.
            val removed = HashSet<String>()
            for ((key, at) in merged.gone) {
                if (key.startsWith("song:")) continue
                val row = rows[key] ?: continue
                if (row.date * 1000 > at) continue
                for (c in db.miscQueries.postCopiesOf(row.unique_id).executeAsList()) library.repo.removePost(c.chat_id, c.message_id)
                library.repo.removePost(row.chat_id, row.message_id)
                removed += key
            }
            songsChanged += removed.size
            if (merged.gone != local.state.gone) library.setPref(GONE_KEY, JsonObject(merged.gone.mapValues { JsonPrimitive(it.value) }).toString())

            val have = db.lanQueries.allPlaylistsWithUuid().executeAsList().associateBy { it.uuid }
            val localEntries = db.lanQueries.allEntries().executeAsList()
            for ((uuid, mp) in merged.playlists) {
                val lp = have[uuid]
                val pid: Long
                if (lp == null) {
                    if (mp.deletedAt != null) continue
                    db.playlistsQueries.insertPlaylist(mp.name, uuid, mp.modifiedAt, null)
                    pid = db.playlistsQueries.lastInsertRowId().executeAsOne()
                    playlistsChanged++
                } else {
                    pid = lp.id
                    if (lp.name != mp.name || lp.modified_at != mp.modifiedAt || lp.deleted_at != mp.deletedAt) {
                        db.lanQueries.setPlaylistMeta(mp.name, mp.modifiedAt, mp.deletedAt, pid)
                        playlistsChanged++
                    }
                }
                val localOrder = local.order[uuid].orEmpty()
                val remoteWins = lp == null || (remote.state.playlists[uuid]?.modifiedAt ?: 0L) > lp.modified_at
                val base = if (remoteWins) remote.order[uuid].orEmpty() else localOrder
                val live = mp.items.filter { it.value.live() }.keys
                val ordered = base.filter { it in live } + live.filter { it !in base }.sorted()
                val position = ordered.withIndex().associate { it.value to (it.index + 1).toLong() }
                val here = localEntries.filter { it.playlist_id == pid }.associateBy { it.unique_id }
                for ((uid, item) in mp.items) {
                    val row = rows[uid] ?: continue
                    val removedAt = if (item.live()) null else item.removedAt
                    val pos = position[uid] ?: 0L
                    val old = here[uid]
                    if (old != null && old.added_at == item.addedAt && old.removed_at == removedAt && (removedAt != null || old.position == pos)) continue
                    db.lanQueries.insertEntryIfMissing(pid, row.chat_id, row.message_id, item.addedAt, removedAt, pos)
                    db.lanQueries.updateEntry(item.addedAt, removedAt, pos, pid, row.chat_id, row.message_id)
                    playlistsChanged++
                }
            }
        }
        return ApplyResult(songsChanged, playlistsChanged, wantCovers)
    }
}

