package io.max.paperplay.core.metadata

/**
 * The languages a piece of text is probably in, from the scripts it is written in. Latin letters say nothing (English, Spanish and a
 * Hindi title in English letters look the same), so text in them gives an empty list. A text of Chinese characters only could be
 * Chinese or Japanese, so it gives both.
 */
fun languagesOfText(vararg texts: String): List<String> {
    var kana = false; var hangul = false; var han = false
    val found = LinkedHashSet<String>()
    for (t in texts) for (ch in t) {
        when (ch.code) {
            in 0x3040..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9F -> kana = true
            in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> hangul = true
            in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> han = true
            in 0x0900..0x097F -> found += "Hindi"
            in 0x0980..0x09FF -> found += "Bengali"
            in 0x0A00..0x0A7F -> found += "Punjabi"
            in 0x0A80..0x0AFF -> found += "Gujarati"
            in 0x0B80..0x0BFF -> found += "Tamil"
            in 0x0C00..0x0C7F -> found += "Telugu"
            in 0x0C80..0x0CFF -> found += "Kannada"
            in 0x0D00..0x0D7F -> found += "Malayalam"
            in 0x0600..0x06FF, in 0x0750..0x077F -> found += "Arabic"
            in 0x0400..0x04FF -> found += "Russian"
            in 0x0E00..0x0E7F -> found += "Thai"
            in 0x0590..0x05FF -> found += "Hebrew"
            in 0x0370..0x03FF -> found += "Greek"
        }
    }
    val out = ArrayList<String>()
    if (kana) out += "Japanese"
    if (hangul) out += "Korean"
    if (han && !kana && !hangul) { out += "Chinese"; out += "Japanese" }
    out += found
    return out
}

private val topicWords = listOf(
    "japanese" to "Japanese", "j-pop" to "Japanese", "jpop" to "Japanese",
    "korean" to "Korean", "k-pop" to "Korean", "kpop" to "Korean",
    "bollywood" to "Hindi", "hindi" to "Hindi",
    "mollywood" to "Malayalam", "malayalam" to "Malayalam",
    "tollywood" to "Telugu", "telugu" to "Telugu",
    "kollywood" to "Tamil", "tamil" to "Tamil",
    "kannada" to "Kannada", "sandalwood" to "Kannada",
    "punjabi" to "Punjabi", "bengali" to "Bengali", "bangla" to "Bengali", "gujarati" to "Gujarati",
    "spanish" to "Spanish", "latin" to "Spanish", "french" to "French", "german" to "German",
    "arabic" to "Arabic", "turkish" to "Turkish", "russian" to "Russian",
    "chinese" to "Chinese", "mandarin" to "Chinese", "c-pop" to "Chinese", "cpop" to "Chinese",
    "hollywood" to "English", "english" to "English",
)

/** The language a Telegram topic is named for ("Japanese", "Bollywood", "Mollywood"), or null if its name says none. */
fun topicLanguage(topicName: String): String? {
    val n = topicName.lowercase()
    return topicWords.firstOrNull { (word, _) -> word in n }?.second
}

/** Words that may stand around a language in a search ("japanese songs", "lang:hindi") and mean nothing by themselves. */
val languageFiller = setOf("song", "songs", "music", "in", "lang", "language", "tracks", "track")

/** True when [word] (lower case, the start of a language is enough) names one of [languages]. */
fun languageMatches(word: String, languages: Collection<String>): Boolean = word.length >= 3 && languages.any { it.lowercase().startsWith(word) }
