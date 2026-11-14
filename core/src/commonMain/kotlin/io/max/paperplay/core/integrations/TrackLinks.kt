package io.max.paperplay.core.integrations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The page of one song on one service. [service] is shown to the person and [url] is what gets copied. */
class TrackLink(val service: String, val url: String)

private val platformNames = listOf(
    "spotify" to "Spotify", "appleMusic" to "Apple Music", "deezer" to "Deezer", "tidal" to "Tidal",
    "amazonMusic" to "Amazon Music", "youtubeMusic" to "YouTube Music", "qobuz" to "Qobuz",
)

/** The song's own page on each service that song.link knows, by display name. */
fun linksFromSongLink(json: String): Map<String, String> {
    val by = Json.parseToJsonElement(json).jsonObject["linksByPlatform"]?.jsonObject ?: return emptyMap()
    return buildMap {
        for ((key, label) in platformNames) by[key]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull?.let { put(label, it) }
    }
}

/**
 * The Deezer page of the result that is the song: same title, the artist inside the result's, length within 5 seconds.
 * [json] is Deezer's search answer. Null when nothing fits, so a wrong song is never offered.
 */
fun deezerLinkFrom(json: String, title: String, artist: String, durationSec: Int): String? {
    val wantTitle = comparableText(searchTitle(cleanTitle(title, artist)))
    val wantArtist = if (artist == "Unknown artist") "" else comparableText(firstArtist(artist))
    if (wantTitle.isEmpty()) return null
    val results = Json.parseToJsonElement(json).jsonObject["data"] as? JsonArray ?: return null
    for (r in results.map { it.jsonObject }) {
        val length = r["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if (durationSec > 0 && length > 0 && kotlin.math.abs(length - durationSec) > 5) continue
        if (comparableText(searchTitle(r["title"]?.jsonPrimitive?.contentOrNull ?: "")) != wantTitle) continue
        val by = r["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        if (wantArtist.isNotEmpty() && wantArtist !in comparableText(by)) continue
        r["link"]?.jsonPrimitive?.contentOrNull?.let { return it }
    }
    return null
}

/**
 * The real pages of a song that is not in the library: its own Spotify page, then the pages song.link knows on the other services,
 * then Apple Music and Deezer found by title, artist and length when song.link missed them. A service with no real match is left out;
 * there are no search pages. Spotify first, Apple Music second.
 */
suspend fun resolveLinks(http: HttpFetcher, trackKey: String, title: String, artist: String, durationSec: Int): List<TrackLink> {
    val found = LinkedHashMap<String, String>()
    spotifyLinkOf(trackKey)?.let { spotify ->
        found["Spotify"] = spotify
        runCatching {
            val res = http.send(HttpRequest("https://api.song.link/v1-alpha.1/links?url=${percentEncode(spotify)}&userCountry=US", headers = mapOf("User-Agent" to "PaperPlay")))
            if (res.status == 200) found.putAll(linksFromSongLink(res.text))
        }
    }
    if ("Apple Music" !in found) runCatching { appleLinkFor(http, title, artist, durationSec, pauseMs = 0) }.getOrNull()?.let { found["Apple Music"] = it }
    if ("Deezer" !in found) runCatching {
        val q = percentEncode("${firstArtist(artist)} ${searchTitle(cleanTitle(title, artist))}".trim())
        val res = http.send(HttpRequest("https://api.deezer.com/search?q=$q&limit=10"))
        if (res.status == 200) deezerLinkFrom(res.text, title, artist, durationSec) else null
    }.getOrNull()?.let { found["Deezer"] = it }
    val order = platformNames.map { it.second }
    return found.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) order.size else i } }.map { TrackLink(it.key, it.value) }
}
