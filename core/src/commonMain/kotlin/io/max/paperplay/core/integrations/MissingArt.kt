package io.max.paperplay.core.integrations

import io.max.paperplay.core.backup.Fs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cover art for tracks that are not in the library, so they can be listed greyed out with their art. The art comes once from
 * Spotify's oEmbed page and is saved as a file in the covers folder, which the usual cover loader can show. A few at a time.
 */
class MissingArt(private val http: HttpFetcher, private val dataDir: String) {
    private val known = HashMap<String, String?>()
    private val lock = Mutex()
    private val gate = Semaphore(3)

    /** The saved cover file of a Spotify track, or null when the track has no id or Spotify has no art for it. */
    suspend fun artFor(trackKey: String): String? {
        val id = spotifyLinkOf(trackKey)?.substringAfterLast('/') ?: return null
        val folder = dataDir.trimEnd('/', '\\') + "/covers"
        val path = "$folder/spotify_$id.jpg"
        if (Fs.exists(path)) return path
        lock.withLock { if (known.containsKey(id)) return known[id] }
        val saved = gate.withPermit {
            runCatching {
                val meta = http.send(HttpRequest("https://open.spotify.com/oembed?url=${percentEncode("https://open.spotify.com/track/$id")}", headers = mapOf("User-Agent" to "PaperPlay")))
                if (meta.status != 200) return@runCatching null
                val url = Json.parseToJsonElement(meta.text).jsonObject["thumbnail_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val image = http.send(HttpRequest(url))
                if (image.status != 200 || image.body.isEmpty()) return@runCatching null
                Fs.mkdirs(folder)
                Fs.write(path, image.body)
                path
            }.getOrNull()
        }
        lock.withLock { known[id] = saved }
        return saved
    }
}
