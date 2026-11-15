package io.max.paperplay.ui.logic

import io.max.paperplay.ui.state.SongUi

/** Lowercase, accent-free words of a search query; punctuation separates them, combining marks of Indian scripts stay inside a word. */
fun searchWords(query: String): List<String> = io.max.paperplay.core.metadata.searchWordsOf(query)

/** 0 when some word of the query matches nothing in the song; otherwise higher is a better match (see [io.max.paperplay.core.metadata.songSearchScore]). */
fun SongUi.searchScore(query: io.max.paperplay.core.metadata.SearchQuery): Int =
    io.max.paperplay.core.metadata.songSearchScore(query, title, artist, album, genre, year, languages, plays, favorite)

/** True when every word of the query matches the song's title, artist, album, genre or year. */
fun SongUi.matches(query: String): Boolean = searchScore(io.max.paperplay.core.metadata.SearchQuery(query)) > 0

/** The songs that match [query], best match first; all of them, in their order, for a blank query. */
fun filterSongs(songs: List<SongUi>, query: String): List<SongUi> {
    val q = io.max.paperplay.core.metadata.SearchQuery(query)
    if (q.isEmpty) return songs
    return songs.mapNotNull { s -> s.searchScore(q).takeIf { it > 0 }?.let { s to it } }.sortedByDescending { it.second }.map { it.first }
}

/** Ways to sort a song list. Each puts the most useful end first. */
enum class SongSort(val label: String) {
    Added("Recently added"),
    Title("Title"),
    Artist("Artist"),
    Album("Album"),
    Quality("Quality"),
    Size("Size"),
    Duration("Length"),
    Year("Year"),
    Plays("Most played"),
}

private fun text(a: String, b: String) = a.lowercase().compareTo(b.lowercase())

fun sortSongs(songs: List<SongUi>, by: SongSort, reverse: Boolean): List<SongUi> {
    val cmp: Comparator<SongUi> = when (by) {
        SongSort.Added -> Comparator { a, b -> b.addedAt.compareTo(a.addedAt) }
        SongSort.Title -> Comparator { a, b -> text(a.title, b.title) }
        SongSort.Artist -> Comparator { a, b -> text(a.artist, b.artist).takeIf { it != 0 } ?: text(a.album, b.album) }
        SongSort.Album -> Comparator { a, b -> text(a.album, b.album).takeIf { it != 0 } ?: text(a.title, b.title) }
        SongSort.Quality -> Comparator { a, b -> b.qualityRank.compareTo(a.qualityRank) }
        SongSort.Size -> Comparator { a, b -> b.sizeBytes.compareTo(a.sizeBytes) }
        SongSort.Duration -> Comparator { a, b -> b.durationSec.compareTo(a.durationSec) }
        SongSort.Year -> Comparator { a, b -> (b.year ?: 0).compareTo(a.year ?: 0) }
        SongSort.Plays -> Comparator { a, b -> b.plays.compareTo(a.plays) }
    }
    val sorted = songs.sortedWith(cmp)
    return if (reverse) sorted.reversed() else sorted
}

/** "3:05" or "1:02:03". */
fun formatDuration(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}
