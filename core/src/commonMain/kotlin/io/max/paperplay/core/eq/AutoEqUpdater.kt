package io.max.paperplay.core.eq

import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import io.max.paperplay.player.ParametricEqCodec
import io.max.paperplay.player.ParametricEqProfile

data class AutoEqRemoteItem(
    val model: String,
    val source: String,
    val form: String,
    val relativeDir: String,
)

data class AutoEqIndexRefreshResult(
    val notModified: Boolean,
    val etag: String?,
    val items: List<AutoEqRemoteItem>,
)

/**
 * Optional online AutoEQ index updater against `jaakkopasanen/AutoEq` (MIT) with `ETag` caching.
 * Fetches `results/INDEX.md` and downloads individual `ParametricEQ.txt` files on demand.
 */
class AutoEqUpdater(private val http: HttpFetcher) {
    private val linkRe = Regex("""^\s*-\s*\[([^\]]+)\]\(\./([^)]+)\)\s+by\s+(.+?)\s*$""")

    suspend fun fetchIndex(previousEtag: String?): AutoEqIndexRefreshResult {
        val headers = buildMap {
            put("User-Agent", "PaperPlay-AutoEQ")
            if (!previousEtag.isNullOrBlank()) put("If-None-Match", previousEtag)
        }
        val resp = http.send(HttpRequest(INDEX_URL, headers = headers))
        if (resp.status == 304) {
            return AutoEqIndexRefreshResult(notModified = true, etag = previousEtag, items = emptyList())
        }
        if (resp.status !in 200..299) {
            error("AutoEQ index HTTP ${resp.status}")
        }
        val etag = resp.headers["etag"]
        val items = parseIndexMarkdown(resp.text)
        return AutoEqIndexRefreshResult(notModified = false, etag = etag, items = items)
    }

    internal fun parseIndexMarkdown(md: String): List<AutoEqRemoteItem> {
        val out = ArrayList<AutoEqRemoteItem>()
        for (line in md.lineSequence()) {
            val m = linkRe.matchEntire(line) ?: continue
            val model = m.groupValues[1].trim()
            val rel = m.groupValues[2].trim().trimEnd('/')
            val segs = rel.split('/')
            val source = segs.getOrNull(0)?.replace("%20", " ") ?: m.groupValues[3].trim()
            val rawForm = segs.getOrNull(1)?.replace("%20", " ").orEmpty().lowercase()
            val form = when {
                "in-ear" in rawForm || "iem" in rawForm -> "in-ear"
                "earbud" in rawForm -> "earbud"
                "over-ear" in rawForm || "on-ear" in rawForm -> "over-ear"
                else -> rawForm
            }
            out.add(AutoEqRemoteItem(model = model, source = source, form = form, relativeDir = rel))
        }
        return out
    }

    suspend fun fetchParametricProfile(item: AutoEqRemoteItem): ParametricEqProfile? {
        val encodedFile = encodePathSegment("${item.model} ParametricEQ.txt")
        val url = "$RESULTS_BASE_URL/${item.relativeDir}/$encodedFile"
        val resp = http.send(HttpRequest(url, headers = mapOf("User-Agent" to "PaperPlay-AutoEQ")))
        if (resp.status !in 200..299) return null
        return ParametricEqCodec.parseEqualizerApo(
            text = resp.text,
            name = "${item.model} (${item.source})",
            source = "AutoEQ / ${item.source}",
            id = "autoeq-online-${item.relativeDir.hashCode()}",
        )
    }

    private fun encodePathSegment(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val ch = (b.toInt() and 0xFF).toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") {
                append(ch)
            } else {
                append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    companion object {
        const val RESULTS_BASE_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        const val INDEX_URL = "$RESULTS_BASE_URL/INDEX.md"
    }
}
