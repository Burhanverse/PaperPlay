package io.max.paperplay.core.integrations

import io.max.paperplay.core.library.allSongsNow
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.db.Missing_tracks
import io.max.paperplay.core.db.Playlist_songs
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.MissingTrack
import io.max.paperplay.core.library.Playlist
import io.max.paperplay.core.library.PlaylistSong
import io.max.paperplay.core.library.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock

/** A track listed by a streaming service: only its details, never its audio. */
data class ImportedTrack(
    /** The service's id for the track, such as `spotify:track:...`. */
    val key: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    /** When it was added to the playlist, if the source says (milliseconds since epoch). */
    val addedAtMs: Long? = null,
)

class ImportError(message: String) : Exception(message)

data class FetchedPlaylist(val name: String, val tracks: List<ImportedTrack>)

private val spotifyPlaylist = Regex("""(?:open\.spotify\.com/(?:[\w-]+/)?playlist/|spotify:playlist:)([A-Za-z0-9]{22})""")

/** The playlist id in a Spotify link, or null if it isn't one. */
fun spotifyPlaylistId(link: String): String? = spotifyPlaylist.find(link)?.groupValues?.get(1)

private const val BROWSER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36"

/**
 * Reads a public playlist from Spotify's embed page, which needs no account. This isn't an
 * official API: Spotify can change the page or limit how many tracks it lists, so a failure
 * here must never touch what's already imported.
 */
suspend fun fetchSpotifyPlaylist(id: String, http: HttpFetcher): FetchedPlaylist {
    val res = http.send(HttpRequest("https://open.spotify.com/embed/playlist/$id", headers = mapOf("User-Agent" to BROWSER_AGENT)))
    if (res.status == 404) throw ImportError("Spotify can't find that playlist. It may be private.")
    if (res.status != 200) throw ImportError("Spotify answered with error ${res.status}.")
    return parseSpotifyEmbed(res.text)
}

private val nextData = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

/** Pulls the playlist out of the embed page's HTML. */
fun parseSpotifyEmbed(html: String): FetchedPlaylist {
    val changed = ImportError("Spotify's page has changed, so this link can't be read.")
    val data = nextData.find(html) ?: throw changed
    val json = try {
        Json.parseToJsonElement(data.groupValues[1])
    } catch (_: Exception) {
        throw changed
    }
    val list = findKey(json, "trackList") as? JsonArray
    if (list == null || list.isEmpty()) throw ImportError("That playlist is empty or private.")
    val tracks = list.mapNotNull { t ->
        val o = t as? JsonObject ?: return@mapNotNull null
        val uri = o.str("uri") ?: return@mapNotNull null
        val title = o.str("title") ?: return@mapNotNull null
        // The page separates artists with a non-breaking space.
        val artist = (o.str("subtitle") ?: "").replace(' ', ' ').trim()
        ImportedTrack(uri, title, artist, millisToSeconds(o["duration"]))
    }
    val name = (findKey(json, "name") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return FetchedPlaylist(if (!name.isNullOrEmpty()) name else "Imported playlist", tracks)
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun millisToSeconds(ms: JsonElement?): Int {
    val p = ms as? JsonPrimitive ?: return 0
    if (p.isString) return 0
    return ((p.doubleOrNull ?: return 0) / 1000).roundToInt()
}

private fun findKey(node: JsonElement?, key: String): JsonElement? {
    when (node) {
        is JsonObject -> {
            if (key in node) return node[key]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            for (v in node.values) findKey(v, key)?.let { return it }
        }
        is JsonArray -> for (v in node) findKey(v, key)?.let { return it }
        else -> {}
    }
    return null
}

private val credit = Regex("""(?:[\[(]|\s[-–]\s|\s)(?:feat|ft|featuring)\b\.?\s*([^\])]*)[\])]?""", RegexOption.IGNORE_CASE)
private val remasterBrackets = Regex("""\s*[\[(][^\])]*remaster[^\])]*[\])]""", RegexOption.IGNORE_CASE)
private val remasterDash = Regex("""\s+[-–]\s+[^-–]*remaster.*$""", RegexOption.IGNORE_CASE)
private const val SAME_SONG = "mono|stereo|single version|album version|original version|radio edit|original mix|bonus track"
private val versionTags = Regex("""\s*[\[(](?:$SAME_SONG)[\])]""", RegexOption.IGNORE_CASE)
/** Spotify writes the same tag after a dash: "Song (feat. X) - Radio Edit". */
private val dashVersionTag = Regex("""\s+[-–—]\s+(?:$SAME_SONG)\s*$""", RegexOption.IGNORE_CASE)
private const val FROM_FILM = "(?:as )?(?:featured|heard) in|(?:music |theme )?from"
private val filmBrackets = Regex("""\s*[\[(](?:$FROM_FILM)\b[^\])]*[\])]""", RegexOption.IGNORE_CASE)
private val filmDash = Regex("""\s+[-–—]\s+(?:$FROM_FILM)\b.*$""", RegexOption.IGNORE_CASE)
/** "(Male Version)" and "(Male)" are one tag, also after a dash. */
private val versionInBrackets = Regex("""(?<=\S)\s+version(?=\s*[\])])""", RegexOption.IGNORE_CASE)
private val versionAfterDash = Regex("""(\s[-–—]\s[^-–—]*\S)\s+version\s*$""", RegexOption.IGNORE_CASE)
private val brackets = Regex("""\s*[\[(]([^\])]*)[\])]""")

/** Letters without their accents, so "Beyoncé" is "Beyonce". */
internal expect fun foldAccents(s: String): String

/** Drops a bracket that only names the song's own artists: "Annihilate (...) (Metro Boomin & Swae Lee, Lil Wayne, Offset)". */
private fun dropArtistBracket(title: String, artist: String): String {
    if ('(' !in title && '[' !in title) return title
    val names = artistsOf(artist).toSet()
    return title.replace(brackets) { m -> if (artistsOf(m.groupValues[1]).any { it in names }) "" else m.value }.ifBlank { title }
}

/** Title with featured-artist credits and remaster/version tags removed, so one song from different releases gets one key. */
fun looseTitleKey(title: String, artist: String): String {
    // Most titles have none of what is taken out below, and this runs for every song of the library and every post of the index.
    if (title.none { it in "([-–—|/" } && !title.contains("feat", ignoreCase = true) && !title.contains("ft", ignoreCase = true)) return comparableText(foldAccents(title))
    val t = cleanTitle(title, artist)
        .replace(remasterBrackets, "")
        .replace(remasterDash, "")
        .replace(versionTags, "")
        .replace(filmBrackets, "")
        .replace(filmDash, "")
        .replace(dashVersionTag, "")
        .replace(credit, "")
        .replace(versionInBrackets, "")
        .replace(versionAfterDash, "$1")
    return comparableText(foldAccents(searchTitle(dropArtistBracket(t, artist))))
}

private fun artistsOf(artist: String): List<String> = splitArtists(foldAccents(artist)).map(::comparableText).filter { it.isNotEmpty() }

private val ellipsis = Regex("""(?:\.{3}|…)[\s)\]]*$""")

/** A title that was cut off: it ends in dots, has a bracket that never closes, or is as long as a file name may get. */
internal fun isCutTitle(title: String): Boolean =
    title.length >= 60 || ellipsis.containsMatchIn(title) || title.count { it == '(' || it == '[' } > title.count { it == ')' || it == ']' }

private fun wholeKey(title: String, artist: String) = comparableText(foldAccents(cleanTitle(title, artist)))

/** The start that a title that was cut off still has; null when it was not cut or the start is too short to prove anything. */
private fun cutStart(title: String, artist: String): String? = if (isCutTitle(title)) wholeKey(title, artist).takeIf { it.length >= 20 } else null

/** Artists credited on a track: its artist field plus any "feat." names in the title. */
private fun credited(title: String, artist: String): List<String> =
    artistsOf(artist) + credit.findAll(title).flatMap { artistsOf(it.groupValues[1]) }

/** How sure a library song is the same recording as a wanted track. */
enum class MatchKind { Sure, Maybe }

private fun sharesArtist(s: Song, title: String, artist: String): Boolean {
    val mine = credited(s.title, s.artist)
    return credited(title, artist).any { a -> mine.any { b -> a in b || b in a } }
}

/**
 * Same song if the loose title agrees and an artist is shared. With lengths known and within 8 s
 * (3 s when the song's title was cut off and only its start agrees) that is [MatchKind.Sure]; a different or unknown length is only [MatchKind.Maybe], for the owner to confirm.
 */
fun matchKind(s: Song, title: String, artist: String, durationSec: Int): MatchKind? {
    val key = looseTitleKey(title, artist)
    if (key.isEmpty()) return null
    val same = looseTitleKey(s.title, s.artist) == key
    if (!same && cutStart(s.title, s.artist)?.let { wholeKey(title, artist).startsWith(it) } != true) return null
    if (!sharesArtist(s, title, artist)) return null
    val known = s.duration_sec > 0 && durationSec > 0
    return if (known && abs(s.duration_sec - durationSec) <= (if (same) 8 else 3)) MatchKind.Sure else MatchKind.Maybe
}

fun matchesTrack(s: Song, title: String, artist: String, durationSec: Int): Boolean =
    matchKind(s, title, artist, durationSec) == MatchKind.Sure

private fun Song.lossless() = isLossless(mime, file_name)

/** The best copy of a track: lossless first, then the largest file. */
fun bestMatch(songs: Iterable<Song>, title: String, artist: String, durationSec: Int): Song? {
    var best: Song? = null
    for (s in songs) {
        if (!matchesTrack(s, title, artist, durationSec)) continue
        val b = best
        if (b == null || (if (s.lossless() != b.lossless()) s.lossless() else s.size > b.size)) best = s
    }
    return best
}

/**
 * The playlist and missing-track tables for import and matching. Rows the generated queries
 * don't cover go through [driver] directly, then listeners are told.
 */
class ImportStore(val repo: LibraryRepo, private val driver: SqlDriver, val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val lists get() = repo.db.playlistsQueries

    init {
        // Tracks the owner matched by hand, so a refresh does not list them as missing again.
        // Looked up first: a screen makes one of these when it opens, and creating, even for nothing, waits for whoever is writing.
        val there = driver.executeQuery(null, "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'import_accepted'", { c -> QueryResult.Value(c.next().value) }, 0).value
        if (!there) driver.execute(null, "CREATE TABLE IF NOT EXISTS import_accepted (playlist_id INTEGER NOT NULL, track_key TEXT NOT NULL, unique_id TEXT NOT NULL, PRIMARY KEY (playlist_id, track_key))", 0)
    }

    fun accept(playlistId: Long, trackKey: String, uniqueId: String) {
        driver.execute(null, "INSERT OR REPLACE INTO import_accepted (playlist_id, track_key, unique_id) VALUES (?, ?, ?)", 3) { bindLong(0, playlistId); bindString(1, trackKey); bindString(2, uniqueId) }
    }

    /** Keys of tracks matched by hand whose song is still in the playlist. */
    fun accepted(playlistId: Long): Set<String> = driver.executeQuery(
        null,
        "SELECT a.track_key FROM import_accepted a JOIN songs s ON s.unique_id = a.unique_id JOIN playlist_songs p ON p.playlist_id = a.playlist_id " +
            "AND p.chat_id = s.chat_id AND p.message_id = s.message_id AND p.removed_at IS NULL WHERE a.playlist_id = ?",
        { c -> val out = HashSet<String>(); while (c.next().value) out += c.getString(0)!!; app.cash.sqldelight.db.QueryResult.Value<Set<String>>(out) }, 1,
    ) { bindLong(0, playlistId) }.value

    fun <T> transaction(block: () -> T): T = repo.db.transactionWithResult { block() }

    fun livePlaylists(): List<Playlist> = lists.livePlaylists().executeAsList()
    fun songs(): List<Song> = repo.db.allSongsNow()

    /** Changes when songs or waiting tracks came, went or got another title, artist or length. */
    fun libraryMark(): List<Long> = driver.executeQuery(
        null,
        "SELECT COUNT(*), COALESCE(SUM(duration_sec), 0), COALESCE(SUM(length(title) + length(artist)), 0), COALESCE(SUM(message_id), 0), (SELECT COUNT(*) FROM missing_tracks) FROM songs",
        { c -> c.next(); QueryResult.Value(List(5) { c.getLong(it)!! }) }, 0,
    ).value

    fun missing(playlistId: Long): List<MissingTrack> = lists.missingTracks(playlistId).executeAsList()
    fun createPlaylist(name: String, sourceUrl: String): Long = repo.createPlaylist(name, sourceUrl)

    fun entries(playlistId: Long): List<PlaylistSong> = driver.executeQuery(
        null,
        "SELECT playlist_id, chat_id, message_id, added_at, source_added_at, removed_at, position FROM playlist_songs WHERE playlist_id = ?",
        { c ->
            val rows = mutableListOf<PlaylistSong>()
            while (c.next().value) {
                rows += Playlist_songs(c.getLong(0)!!, c.getLong(1)!!, c.getLong(2)!!, c.getLong(3)!!, c.getLong(4), c.getLong(5), c.getLong(6)!!)
            }
            QueryResult.Value(rows)
        },
        1,
    ) { bindLong(0, playlistId) }.value

    fun insertMissing(playlistId: Long, t: ImportedTrack, position: Int) = lists.insertMissingTrack(
        Missing_tracks(playlistId, t.key, t.title, t.artist, t.durationSec.toLong(), position.toLong(), t.addedAtMs),
    )

    fun setMissingSourceAdded(playlistId: Long, key: String, ms: Long) =
        exec("UPDATE missing_tracks SET source_added_at = ? WHERE playlist_id = ? AND track_key = ?", "missing_tracks") {
            bindLong(0, ms); bindLong(1, playlistId); bindString(2, key)
        }

    fun deleteMissing(playlistId: Long, key: String) =
        exec("DELETE FROM missing_tracks WHERE playlist_id = ? AND track_key = ?", "missing_tracks") {
            bindLong(0, playlistId); bindString(1, key)
        }

    /** A new entry; an existing one (even a removed one) is left alone. */
    fun addEntry(playlistId: Long, chat: Long, message: Long, addedAt: Long, sourceAddedAt: Long?) = exec(
        "INSERT OR IGNORE INTO playlist_songs (playlist_id, chat_id, message_id, added_at, source_added_at, removed_at, position) VALUES (?, ?, ?, ?, ?, NULL, ${nextPosition(playlistId)})",
        "playlist_songs",
    ) { bindLong(0, playlistId); bindLong(1, chat); bindLong(2, message); bindLong(3, addedAt); bindLong(4, sourceAddedAt) }

    /** The entry is in the playlist (again) from [addedAt]; its source date changes only if [sourceAddedAt] is given. */
    fun upsertEntry(
        playlistId: Long, chat: Long, message: Long, addedAt: Long, sourceAddedAt: Long?, keepSource: Boolean, position: Long? = null,
    ) = exec(
        "INSERT INTO playlist_songs (playlist_id, chat_id, message_id, added_at, source_added_at, removed_at, position) " +
            "VALUES (?, ?, ?, ?, ?, NULL, ${position?.toString() ?: nextPosition(playlistId)}) " +
            "ON CONFLICT (playlist_id, chat_id, message_id) DO UPDATE SET added_at = excluded.added_at, removed_at = NULL, position = excluded.position" +
            (if (keepSource) "" else ", source_added_at = excluded.source_added_at"),
        "playlist_songs",
    ) { bindLong(0, playlistId); bindLong(1, chat); bindLong(2, message); bindLong(3, addedAt); bindLong(4, sourceAddedAt) }

    fun setEntrySourceAdded(playlistId: Long, chat: Long, message: Long, ms: Long?) = exec(
        "UPDATE playlist_songs SET source_added_at = ? WHERE playlist_id = ? AND chat_id = ? AND message_id = ?",
        "playlist_songs",
    ) { bindLong(0, ms); bindLong(1, playlistId); bindLong(2, chat); bindLong(3, message) }

    fun markEntryRemoved(playlistId: Long, chat: Long, message: Long, at: Long) = exec(
        "UPDATE playlist_songs SET removed_at = ? WHERE playlist_id = ? AND chat_id = ? AND message_id = ?",
        "playlist_songs",
    ) { bindLong(0, at); bindLong(1, playlistId); bindLong(2, chat); bindLong(3, message) }

    /** Listeners of the generated queries re-run after raw writes. */
    fun announceChange() = driver.notifyListeners("playlist_songs", "missing_tracks", "playlists")

    private fun exec(sql: String, table: String, binders: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit) {
        driver.execute(null, sql, countParams(sql), binders).value
        driver.notifyListeners(table)
    }

    private fun countParams(sql: String) = sql.count { it == '?' }
}

/** Review list: waiting tracks of [tracks] with the songs that might be them: same artist and the same title, or one title begins with the other. */
fun reviewCandidates(store: ImportStore, tracks: Iterable<MissingTrack>): Map<String, List<Song>> {
    val songs = store.songs().map { looseTitleKey(it.title, it.artist) to it }.filter { it.first.isNotEmpty() }
    return tracks.associate { t ->
        val key = looseTitleKey(t.title, t.artist)
        fun near(k: String) = k == key || minOf(k.length, key.length) >= 4 && (k.startsWith(key) || key.startsWith(k))
        t.track_key to songs.filter { (k, s) -> near(k) && sharesArtist(s, t.title, t.artist) }
            .sortedWith(compareBy({ abs(it.second.duration_sec - t.duration_sec) > 3 }, { it.first != key }, { abs(it.second.duration_sec - t.duration_sec) }, { !it.second.lossless() }, { -it.second.size }))
            // Copies of one song are one suggestion: the best of them.
            .distinctBy { it.first to it.second.duration_sec }.take(3).map { it.second }
    }
}

/** The owner confirmed [song] is [track]: it joins the playlist and stops waiting. */
fun acceptMatch(store: ImportStore, track: MissingTrack, song: Song) {
    store.transaction {
        store.upsertEntry(
            track.playlist_id, song.chat_id, song.message_id, store.clock() + track.position, track.source_added_at, keepSource = false,
        )
        store.deleteMissing(track.playlist_id, track.track_key)
        store.accept(track.playlist_id, track.track_key, song.unique_id)
    }
    store.announceChange()
}

class ImportResult(
    val playlistId: Long,
    val name: String,
    val matched: Int,
    val missing: Int,
    /** It came from the public page and reached that page's limit, so there may be more. */
    val limited: Boolean = false,
)

/** How many tracks Spotify's public embed page lists, however long the playlist is. */
private const val EMBED_LIMIT = 100

/** SQL for the slot after the last entry of a playlist. */
private fun nextPosition(playlistId: Long) =
    "(SELECT COALESCE(MAX(p.position), 0) + 1 FROM playlist_songs AS p WHERE p.playlist_id = $playlistId)"

/**
 * Creates or refreshes the playlist for [link]. Tracks already in the library join it; the rest
 * wait in its missing list until a matching song appears. A refresh only adds: tracks removed at
 * the source stay.
 *
 * [official] reads the whole list through the owner's Spotify login, when it can.
 */
suspend fun importPlaylist(
    store: ImportStore,
    link: String,
    http: HttpFetcher,
    official: (suspend (String) -> FetchedPlaylist?)? = null,
): ImportResult {
    val id = spotifyPlaylistId(link) ?: throw ImportError("That isn't a Spotify playlist link.")
    val whole = official?.invoke(id)
    val source = whole ?: fetchSpotifyPlaylist(id, http)
    val sourceUrl = "https://open.spotify.com/playlist/$id"

    val existing = store.livePlaylists().firstOrNull { it.source_url == sourceUrl }
    val playlistId = existing?.id ?: store.createPlaylist(source.name, sourceUrl)

    val known = store.missing(playlistId).map { it.track_key }.toMutableSet()
    val accepted = store.accepted(playlistId)
    store.transaction {
        source.tracks.forEachIndexed { i, t ->
            if (t.key in accepted) return@forEachIndexed
            // add() is false for a repeat: playlists can list one track twice.
            if (!known.add(t.key)) {
                if (t.addedAtMs != null) store.setMissingSourceAdded(playlistId, t.key, t.addedAtMs)
            } else {
                store.insertMissing(playlistId, t, i)
            }
        }
    }
    reconcileImports(store)
    stampSourceDates(store, playlistId, source.tracks)
    val missing = store.missing(playlistId)
    val distinct = source.tracks.map { it.key }.toSet().size
    return ImportResult(
        playlistId,
        source.name,
        distinct - missing.size,
        missing.size,
        limited = whole == null && source.tracks.size >= EMBED_LIMIT,
    )
}

/**
 * Records the source's added dates on songs already in the playlist. Run on every refresh, so
 * playlists imported before dates were kept get them too.
 */
fun stampSourceDates(store: ImportStore, playlistId: Long, tracks: List<ImportedTrack>) {
    val dated = tracks.filter { it.addedAtMs != null }
    if (dated.isEmpty()) return
    val entries = store.entries(playlistId).filter { it.removed_at == null }
    val songs = store.songs().associateBy { it.chat_id to it.message_id }
    val byTitle = HashMap<String, MutableList<PlaylistSong>>()
    for (e in entries) {
        val s = songs[e.chat_id to e.message_id] ?: continue
        byTitle.getOrPut(looseTitleKey(s.title, s.artist)) { mutableListOf() } += e
    }
    var changed = false
    store.transaction {
        for (t in dated) {
            for (e in byTitle[looseTitleKey(t.title, t.artist)].orEmpty()) {
                if (e.source_added_at == t.addedAtMs || !matchesTrack(songs.getValue(e.chat_id to e.message_id), t.title, t.artist, t.durationSec)) continue
                store.setEntrySourceAdded(playlistId, e.chat_id, e.message_id, t.addedAtMs)
                changed = true
            }
        }
    }
    if (changed) store.announceChange()
}

/**
 * Matches waiting tracks against the library, and moves imported playlists to a lossless copy
 * when one has appeared. Run when songs are added or synced.
 *
 * Only the playlist entry moves. The old file stays in Telegram: deleting it automatically
 * could lose the wrong song on a bad match. True when a playlist changed.
 */
fun reconcileImports(store: ImportStore): Boolean {
    val imported = store.livePlaylists().filter { it.source_url != null }
    if (imported.isEmpty()) return false
    val library = store.songs()
    val byTitle = library.groupBy { looseTitleKey(it.title, it.artist) }
    val byId = library.associateBy { it.chat_id to it.message_id }
    val cut = library.mapNotNull { s -> cutStart(s.title, s.artist)?.let { it to s } }
    fun sameTitle(title: String, artist: String) = byTitle[looseTitleKey(title, artist)].orEmpty()

    // All comparing comes before the transaction: it takes a while on a phone, and nobody else can write during one.
    val found = imported.map { p ->
        val arrived = store.missing(p.id).mapNotNull { t ->
            val whole = wholeKey(t.title, t.artist)
            val candidates = sameTitle(t.title, t.artist) + cut.filter { whole.startsWith(it.first) }.map { it.second }
            bestMatch(candidates, t.title, t.artist, t.duration_sec.toInt())?.let { t to it }
        }
        val improved = store.entries(p.id).filter { it.removed_at == null }.mapNotNull { e ->
            val song = byId[e.chat_id to e.message_id]?.takeIf { !it.lossless() } ?: return@mapNotNull null
            bestMatch(sameTitle(song.title, song.artist), song.title, song.artist, song.duration_sec.toInt())?.takeIf { it.lossless() }?.let { e to it }
        }
        Triple(p, arrived, improved)
    }
    if (found.all { it.second.isEmpty() && it.third.isEmpty() }) return false

    var changed = false
    val now = store.clock()
    store.transaction {
        for ((p, arrived, improved) in found) {
            // Read again: the owner may have changed the playlist meanwhile.
            val entries = store.entries(p.id)
            val keys = entries.map { it.chat_id to it.message_id }.toMutableSet()
            val waiting = store.missing(p.id).map { it.track_key }.toSet()
            for ((t, song) in arrived) {
                if (t.track_key !in waiting) continue
                // A song someone removed from this playlist stays removed.
                if (keys.add(song.chat_id to song.message_id)) {
                    store.addEntry(p.id, song.chat_id, song.message_id, now + t.position, t.source_added_at)
                }
                store.deleteMissing(p.id, t.track_key)
                changed = true
            }
            for ((e, better) in improved) {
                if (entries.none { it.chat_id == e.chat_id && it.message_id == e.message_id && it.removed_at == null }) continue
                store.markEntryRemoved(p.id, e.chat_id, e.message_id, now)
                store.upsertEntry(p.id, better.chat_id, better.message_id, e.added_at, null, keepSource = true, position = e.position)
                changed = true
            }
        }
    }
    if (changed) store.announceChange()
    return changed
}
