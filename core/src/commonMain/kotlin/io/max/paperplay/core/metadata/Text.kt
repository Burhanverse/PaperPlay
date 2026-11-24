package io.max.paperplay.core.metadata

/** Text reduced to lowercase letters and digits, for comparing names. */
fun comparableText(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

private val artistSplit = Regex("\\s*(?:,|&|;|\\s/\\s|\\bfeat\\.?\\s|\\bft\\.?\\s)\\s*", RegexOption.IGNORE_CASE)

/** First name in `A, B & C`, since multi-artist strings rarely match exactly. */
fun firstArtist(artist: String): String = artist.split(artistSplit).first().trim()

/** Each artist of a credit like "A, B & C feat. D", in order, without empty names. */
fun splitArtists(artist: String): List<String> = artist.split(artistSplit).map { it.trim() }.filter { it.isNotEmpty() }

private val trackPrefix = Regex("^\\s*\\d+\\s*[|/]\\s*\\d+\\s*-\\s*")
private val qualityTag = Regex(
    "\\s*[\\[(](?:flac|mp3|m4a|aac|wav|alac|hi-?res|lossless|\\d+\\s*kbps)[\\])]",
    RegexOption.IGNORE_CASE,
)

/** Removes junk that uploaders add: `1|5 - ` prefixes, `(FLAC)` tags, and the artist repeated at either end. */
fun cleanTitle(title: String, artist: String): String {
    var t = title.replaceFirst(trackPrefix, "").replace(qualityTag, "").trim()
    val name = firstArtist(artist).lowercase()
    if (name.isNotEmpty()) {
        val last = t.lastIndexOf(" - ")
        if (last > 0 && t.substring(last + 3).lowercase().startsWith(name)) t = t.substring(0, last).trim()
        val first = t.indexOf(" - ")
        if (first > 0 && t.substring(0, first).lowercase().startsWith(name)) t = t.substring(first + 3).trim()
    }
    return t.ifEmpty { title }
}

private val creditTag = Regex(
    "\\s*[\\[(](?:feat\\.?|ft\\.?|with |from |explicit|clean|official|lyric|audio|video|remaster)[^\\])]*[\\])]",
    RegexOption.IGNORE_CASE,
)

/** Title for searching: drops `(feat. X)`, `[Explicit]`, `(From "Y")` and similar. Keeps `(Remix)`. */
fun searchTitle(title: String): String = title.replace(creditTag, "").trim().ifEmpty { title }

/** Plain words for a MusicBrainz search, without what its query language reads as syntax. */
fun musicBrainzQuery(term: String): String =
    term.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

/** Wait in ms before retrying a busy MusicBrainz: 4s, 8s, 16s, 32s, then 60s. */
fun busyDelay(attempt: Int): Long = minOf(60, 2 shl attempt) * 1000L

/** Other iTunes countries worth searching when the default one has no match. */
fun extraStorefronts(title: String, artist: String): List<String> {
    val text = "$title $artist"
    val japanese = Regex("[぀-ヿ]").containsMatchIn(text)
    val cjk = Regex("[一-鿿]").containsMatchIn(text)
    return listOfNotNull(if (japanese) "JP" else null, if (cjk && !japanese) "TW" else null, "IN")
}

class LrcLine(val timeMs: Long, val text: String)

private val lrcStamp = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")

/** Parses `[mm:ss.xx] text` lines. Empty when [lrc] has no timestamps. */
fun parseLrc(lrc: String): List<LrcLine> {
    val lines = ArrayList<LrcLine>()
    for (line in lrc.lines()) {
        val text = line.replace(lrcStamp, "").trim()
        for (m in lrcStamp.findAll(line)) {
            val fraction = (m.groupValues[3].ifEmpty { "0" }).padEnd(3, '0').substring(0, 3)
            lines += LrcLine(
                m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fraction.toLong(),
                text,
            )
        }
    }
    return lines.sortedBy { it.timeMs }
}

/** A primary lyric line optionally paired with a romanization or translation line at the same moment. */
data class PairedLrcLine(val timeMs: Long, val text: String, val secondary: String? = null)

/**
 * Pairs [primary] LRC lines with the nearest [secondary] LRC line within [maxOffsetMs].
 * Tolerant of small timestamp offsets, missing secondary lines (without stealing from the next
 * primary line), and duplicate timestamps. Optionally suppresses [secondary] when it matches [primary].
 */
fun pairLrcLines(
    primary: List<LrcLine>,
    secondary: List<LrcLine>,
    maxOffsetMs: Long = 1500L,
    dedupeIdentical: Boolean = true,
): List<PairedLrcLine> {
    if (primary.isEmpty()) return emptyList()
    val secSorted = secondary.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
    if (secSorted.isEmpty()) return primary.map { PairedLrcLine(it.timeMs, it.text) }

    val used = BooleanArray(secSorted.size)
    return primary.mapIndexed { idx, p ->
        if (p.text.isBlank()) return@mapIndexed PairedLrcLine(p.timeMs, p.text)

        var bestIdx = -1
        var bestDiff = Long.MAX_VALUE
        for (j in secSorted.indices) {
            if (used[j]) continue
            val diff = kotlin.math.abs(secSorted[j].timeMs - p.timeMs)
            if (diff <= maxOffsetMs && diff < bestDiff) {
                bestDiff = diff
                bestIdx = j
            }
        }
        if (bestIdx < 0) return@mapIndexed PairedLrcLine(p.timeMs, p.text)

        // Steal guard: if a strictly later non-blank primary line is closer to this secondary line, leave this line unpaired.
        val candTime = secSorted[bestIdx].timeMs
        val nextTime = (idx + 1 until primary.size)
            .firstOrNull { primary[it].text.isNotBlank() && primary[it].timeMs > p.timeMs }
            ?.let { primary[it].timeMs }
        if (nextTime != null && kotlin.math.abs(candTime - nextTime) < bestDiff) {
            return@mapIndexed PairedLrcLine(p.timeMs, p.text)
        }

        used[bestIdx] = true
        val secText = secSorted[bestIdx].text.trim()
        val finalSec = if (dedupeIdentical && secText.equals(p.text.trim(), ignoreCase = true)) null else secText
        PairedLrcLine(p.timeMs, p.text, finalSec)
    }
}

/** Convenience wrapper that parses [primaryLrc] and optional [secondaryLrc] before pairing. */
fun pairLrc(
    primaryLrc: String,
    secondaryLrc: String?,
    maxOffsetMs: Long = 1500L,
    dedupeIdentical: Boolean = true,
): List<PairedLrcLine> {
    val primary = parseLrc(primaryLrc)
    val secondary = secondaryLrc?.let(::parseLrc).orEmpty()
    return pairLrcLines(primary, secondary, maxOffsetMs, dedupeIdentical)
}
