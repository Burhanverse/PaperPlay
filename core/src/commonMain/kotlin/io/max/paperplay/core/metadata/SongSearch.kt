package io.max.paperplay.core.metadata

private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

/** Each string is a plain letter followed by the accented forms that fold to it. */
private val FOLD_GROUPS = listOf(
    "aàáâãäåāăą", "cçćĉċč", "dďđ", "eèéêëēĕėęě", "gĝğġģ", "hĥħ", "iìíîïĩīĭįı", "jĵ", "kķ", "lĺļľŀł", "nñńņň",
    "oòóôõöøōŏő", "rŕŗř", "sśŝşš", "tţťŧ", "uùúûüũūŭůűų", "wŵ", "yýÿŷ", "zźżž",
)
private val FOLD: Map<Char, Char> = buildMap { for (g in FOLD_GROUPS) for (c in g.drop(1)) put(c, g[0]) }

/** Lowercase [text] with Latin accents removed ("Beyoncé" -> "beyonce"); Indic and other scripts and their combining marks are left as they are. */
fun foldSearchText(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text.lowercase()) {
        // A combining accent after a plain Latin letter ("e" + U+0301) is dropped; marks of other scripts stay.
        if (ch in '̀'..'ͯ' && sb.isNotEmpty() && sb.last() in 'a'..'z') continue
        when (ch) {
            'ß' -> sb.append("ss")
            'æ' -> sb.append("ae")
            'œ' -> sb.append("oe")
            else -> sb.append(FOLD[ch] ?: ch)
        }
    }
    return sb.toString()
}

/** Words of [text] for searching: folded, split at punctuation. Combining marks of Indian scripts stay inside a word. */
fun searchWordsOf(text: String): List<String> = foldSearchText(text).split(NON_WORD).filter { it.isNotEmpty() }

/** A search typed by the owner: its words and the same text joined, ready to score many songs. */
class SearchQuery(text: String) {
    private val typed: List<String> = searchWordsOf(text)
    /** A "by" between two parts ("contact by daft punk") only joins title and artist, so it is not a word a song must hold; "stand by me" still finds its song through the other words and the whole phrase. */
    val words: List<String> = typed.filterIndexed { i, w -> !(w == "by" && i > 0 && i < typed.lastIndex && typed.size > 2) }
    val whole: String = typed.joinToString(" ")
    val isEmpty: Boolean get() = words.isEmpty()
}

/** How many single-letter slips a word of [length] letters may have and still count as the same word. */
private fun allowedSlips(length: Int): Int = when {
    length < 4 -> 0
    length < 8 -> 1
    else -> 2
}

/** Edit distance with swapped neighbours counted as one slip; stops early and returns [limit] + 1 once it is certain to be over. */
internal fun slips(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    var prev2 = IntArray(b.length + 1)
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        var rowMin = cur[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
            cur[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > limit) return limit + 1
        val t = prev2; prev2 = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

/** True when [q] is within [limit] slips of some start of [t]; the start may be a few letters shorter or longer than [q]. */
private fun prefixSlips(q: String, t: String, limit: Int): Boolean {
    for (len in (q.length - limit).coerceAtLeast(1)..minOf(t.length, q.length + limit)) {
        if (slips(q, t.substring(0, len), limit) <= limit) return true
    }
    return false
}

/** 0 when [q] matches no word of [tokens]; else exact 100, word start 80, inside a word 50, a typo of a word or of a word's start 35. */
internal fun wordStrength(q: String, tokens: List<String>): Int {
    var best = 0
    val limit = allowedSlips(q.length)
    for (t in tokens) {
        if (t == q) return 100
        val s = when {
            t.startsWith(q) -> 80
            q.length >= 3 && t.contains(q) -> 50
            // A typo is only believed when the first letter is right, which keeps "weeknd" away from unrelated words.
            limit > 0 && t.isNotEmpty() && t[0] == q[0] &&
                (slips(q, t, limit) <= limit || (t.length > q.length && prefixSlips(q, t, limit))) -> 35
            else -> 0
        }
        if (s > best) best = s
    }
    return best
}

/**
 * 0 when some word of [query] matches nothing in the song; otherwise higher is better: title beats artist beats album beats
 * genre and year, an exact word beats a word start beats a part of a word beats a typo, and a title equal to or starting with
 * the whole query comes first. Plays and favorites only break ties.
 */
fun songSearchScore(
    query: SearchQuery, title: String, artist: String, album: String, genre: String?, year: Int?,
    languages: List<String> = emptyList(), plays: Int = 0, favorite: Boolean = false,
): Int {
    if (query.isEmpty) return 1
    val t = searchWordsOf(title); val a = searchWordsOf(artist); val al = searchWordsOf(album)
    val g = searchWordsOf(genre.orEmpty()); val y = year?.toString()
    // "japanese songs" or "lang:hindi": the filler words are dropped as long as another word is left to search for.
    val words = query.words.filter { it !in languageFiller }.ifEmpty { query.words }
    var total = 0
    var inTitle = 0
    for (w in words) {
        val st = wordStrength(w, t)
        if (st > 0) inTitle++
        val best = maxOf(
            st * 4,
            wordStrength(w, a) * 3,
            wordStrength(w, al) * 2,
            maxOf(wordStrength(w, g), if (y == w) 80 else 0, if (languageMatches(w, languages)) 60 else 0),
        )
        if (best == 0) return 0
        total += best
    }
    val titleText = t.joinToString(" ")
    if (titleText == query.whole) total += 2000
    else if (titleText.startsWith(query.whole)) total += 800
    if (inTitle == words.size) total += 300
    // Relevance first; plays and favorites (at most 70) only order songs of equal relevance.
    return total * 1000 + minOf(plays, 50) + if (favorite) 20 else 0
}
