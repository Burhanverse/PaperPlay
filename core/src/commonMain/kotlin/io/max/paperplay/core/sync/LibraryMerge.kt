package io.max.paperplay.core.sync

/** Edit stamp: the clock, but always after [previous] so a local edit beats the version it replaces. */
fun laterStamp(now: Long, previous: Long): Long = if (now > previous) now else previous + 1

/** Per-song state that syncs. [userEdited] tags beat automatic ones; otherwise the later [tagsAt] wins. */
data class SongSync(
    val title: String, val userEdited: Boolean, val tagsAt: Long,
    val favorite: Boolean, val favoriteAt: Long, val plays: Long, val skips: Long, val lastPlayedAt: Long?,
)

/** A playlist item: removal and re-add are decided by whichever change is later. */
data class ItemSync(val addedAt: Long, val removedAt: Long?) {
    val lastChange: Long get() = if (removedAt != null && removedAt > addedAt) removedAt else addedAt
}

/** A track a playlist imported from a link still waits for. */
data class MissingSync(val key: String, val title: String, val artist: String, val durationSec: Long, val position: Long, val sourceAddedAt: Long?)

/** [sourceUrl] is the link the playlist was imported from (so it can be refreshed on any device), [missing] what is not in the library yet. */
data class PlaylistSync(
    val name: String, val modifiedAt: Long, val deletedAt: Long?, val items: Map<String, ItemSync>,
    val sourceUrl: String? = null, val missing: List<MissingSync> = emptyList(),
)

data class LibraryState(
    val songs: Map<String, SongSync> = emptyMap(),
    /** Deleted files by unique id with when. */
    val gone: Map<String, Long> = emptyMap(),
    val playlists: Map<String, PlaylistSync> = emptyMap(),
)

fun remoteTagsWin(local: SongSync, remote: SongSync): Boolean =
    if (remote.userEdited != local.userEdited) remote.userEdited else remote.tagsAt > local.tagsAt

/** Merges [remote] into [local]. Pure; merging agreeing states returns an equal state. Songs only [remote] has are added unless gone. */
fun mergeLibrary(local: LibraryState, remote: LibraryState): LibraryState {
    val gone = HashMap(local.gone)
    for ((k, at) in remote.gone) if ((gone[k] ?: 0) < at) gone[k] = at
    val songs = HashMap<String, SongSync>()
    for ((k, l) in local.songs) if (k !in gone) songs[k] = l
    for ((k, r) in remote.songs) {
        if (k in gone) continue
        val l = songs[k]
        if (l == null) { songs[k] = r; continue }
        var m = l
        if (remoteTagsWin(l, r)) m = m.copy(title = r.title, userEdited = r.userEdited, tagsAt = r.tagsAt)
        if (r.favoriteAt > l.favoriteAt) m = m.copy(favorite = r.favorite, favoriteAt = r.favoriteAt)
        // Each device counts its own, so the higher value is closest without double counting.
        m = m.copy(plays = maxOf(l.plays, r.plays), skips = maxOf(l.skips, r.skips),
            lastPlayedAt = listOfNotNull(l.lastPlayedAt, r.lastPlayedAt).maxOrNull())
        songs[k] = m
    }
    val lists = HashMap(local.playlists)
    for ((u, r) in remote.playlists) {
        val l = lists[u]
        val base = if (l == null || r.modifiedAt > l.modifiedAt) r.copy(items = l?.items ?: emptyMap()) else l
        val items = HashMap(base.items)
        for ((key, ri) in r.items) { val li = items[key]; if (li == null || ri.lastChange > li.lastChange) items[key] = ri }
        // The link and the waiting list only fill in what this device does not have; each device keeps its own once it has them.
        val other = if (base === l) r else l
        lists[u] = base.copy(
            items = items,
            sourceUrl = base.sourceUrl ?: other?.sourceUrl,
            missing = base.missing.ifEmpty { other?.missing.orEmpty() },
        )
    }
    return LibraryState(songs, gone, lists)
}

private fun String?.blankOrUnknown() = isNullOrBlank() || this == "Unknown artist"

/**
 * Song data other than state: [remote] fills what [local] lacks, whichever of the two is manual or automatic. Where both have a
 * value, tags the owner edited win over automatic ones ([remoteTagsWin]); with the same origin the later edit wins. Lyrics are only
 * ever filled in. Pure; [local] comes back unchanged when there is nothing to take.
 */
fun mergeSongData(local: io.max.paperplay.core.library.Song, ls: SongSync, rs: SongSync, remote: SongExtra): io.max.paperplay.core.library.Song {
    val remoteWins = remoteTagsWin(ls, rs)
    fun text(l: String?, r: String?, emptyL: Boolean): String? = when {
        r.blankOrUnknown() -> l
        emptyL -> r
        remoteWins -> r
        else -> l
    }
    val artist = text(local.artist, remote.artist, local.artist.blankOrUnknown()) ?: local.artist
    val album = text(local.album, remote.album, local.album.isBlank()) ?: local.album
    val genre = text(local.genre, remote.genre, local.genre.isNullOrBlank())
    val year = when {
        remote.year == null -> local.year
        local.year == null -> remote.year
        remoteWins -> remote.year
        else -> local.year
    }
    val release = text(local.release_id, remote.releaseId, local.release_id.isNullOrBlank())
    val coverUrl = text(local.cover_url, remote.coverUrl, local.cover_url.isNullOrBlank())
    val source = text(local.metadata_source, remote.metadataSource, local.metadata_source.isNullOrBlank())
    val lyrics = if (local.lyrics.isNullOrBlank() && !remote.lyrics.isNullOrBlank()) remote.lyrics else local.lyrics
    val state = if (remote.enriched && local.enrich_state != io.max.paperplay.core.library.EnrichState.DONE) io.max.paperplay.core.library.EnrichState.DONE else local.enrich_state
    return local.copy(
        artist = artist, album = album, genre = genre, year = year, release_id = release, cover_url = coverUrl,
        metadata_source = source, lyrics = lyrics, enrich_state = state,
    )
}
