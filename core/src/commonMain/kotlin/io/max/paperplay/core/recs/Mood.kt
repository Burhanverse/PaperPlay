package io.max.paperplay.core.recs

import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Today's weather, as far as a mix cares. */
enum class WeatherKind { CLEAR, CLOUDY, RAIN, STORM, SNOW }

/** What a WMO weather code means for a mix. */
fun weatherKindOf(code: Int): WeatherKind? = when (code) {
    0, 1 -> WeatherKind.CLEAR
    2, 3, 45, 48 -> WeatherKind.CLOUDY
    in 51..67, in 80..82 -> WeatherKind.RAIN
    in 71..77, 85, 86 -> WeatherKind.SNOW
    in 95..99 -> WeatherKind.STORM
    else -> null
}

/** A mood a mix can be made for, placed on two axes: how positive (valence) and how intense (energy), -1 to 1. */
data class Mood(
    val id: String,
    val name: String,
    val valence: Double,
    val energy: Double,
    /** Words about this in a song count towards the mood. */
    val theme: String? = null,
    val wantsInstrumental: Boolean = false,
)

val moods = listOf(
    Mood("happy", "Happy", 0.7, 0.4, theme = "happy"),
    Mood("sad", "Sad", -0.7, -0.4, theme = "sad"),
    Mood("drive", "Drive", 0.2, 0.7, theme = "drive"),
    Mood("rain", "Rain", -0.2, -0.4, theme = "rain"),
    Mood("chill", "Chill", 0.3, -0.5),
    Mood("focus", "Focus", 0.0, -0.6, wantsInstrumental = true),
    Mood("party", "Party", 0.6, 0.9, theme = "party"),
    Mood("night", "Late night", -0.1, -0.2, theme = "night"),
    Mood("love", "Love", 0.5, -0.1, theme = "love"),
    Mood("workout", "Workout", 0.2, 0.95),
)

private val genreFeel = linkedMapOf(
    "metal" to (-0.2 to 0.9),
    "punk" to (0.0 to 0.85),
    "hard rock" to (0.0 to 0.8),
    "rock" to (0.1 to 0.6),
    "alternative" to (-0.1 to 0.3),
    "indie" to (0.0 to 0.1),
    "j-pop" to (0.3 to 0.4),
    "k-pop" to (0.4 to 0.6),
    "anime" to (0.2 to 0.5),
    "pop" to (0.4 to 0.4),
    "dance" to (0.6 to 0.8),
    "house" to (0.5 to 0.8),
    "techno" to (0.2 to 0.9),
    "edm" to (0.5 to 0.85),
    "electronic" to (0.2 to 0.6),
    "hip-hop" to (0.1 to 0.6),
    "hip hop" to (0.1 to 0.6),
    "rap" to (0.1 to 0.65),
    "r&b" to (0.2 to -0.1),
    "soul" to (0.2 to -0.1),
    "singer/songwriter" to (-0.1 to -0.4),
    "folk" to (0.0 to -0.4),
    "acoustic" to (0.1 to -0.5),
    "downtempo" to (0.0 to -0.7),
    "ambient" to (0.0 to -0.8),
    "lo-fi" to (0.1 to -0.7),
    "classical" to (0.0 to -0.6),
    "jazz" to (0.2 to -0.4),
    "blues" to (-0.3 to -0.2),
    "soundtrack" to (-0.1 to -0.4),
    "reggae" to (0.5 to -0.1),
    "latin" to (0.5 to 0.6),
    "country" to (0.2 to 0.0),
    "bollywood" to (0.3 to 0.2),
    "tamil" to (0.3 to 0.3),
    "regional indian" to (0.3 to 0.2),
)

private val positive = setOf(
    "happy", "happiness", "joy", "smile", "smiling", "laugh", "laughing", "sunshine", "sunny", "celebrate", "beautiful",
    "wonderful", "alive", "free", "freedom", "shine", "shining", "bright", "dream", "dreams", "hope", "best", "good",
    "great", "fun", "dance", "dancing", "lucky", "golden", "khushi", "muskaan", "hasna", "sapna", "sapne",
)
private val negative = setOf(
    "sad", "cry", "crying", "cried", "tears", "tear", "alone", "lonely", "broken", "hurt", "hurts", "pain", "goodbye",
    "die", "dead", "dying", "death", "lost", "miss", "missing", "gone", "empty", "hate", "fear", "afraid", "sorry",
    "bleed", "grave", "regret", "never", "cold", "numb", "darkness", "dard", "tanha", "tanhai", "aansu", "judaai",
    "bewafa", "gham", "alvida",
)
private val positiveScript = listOf("幸せ", "笑顔", "笑", "喜び", "嬉しい", "楽しい", "希望", "輝", "खुशी", "मुस्कान", "சந்தோஷ")
private val negativeScript = listOf(
    "涙", "悲し", "寂し", "淋し", "泣", "痛", "苦し", "孤独", "さよなら", "サヨナラ", "別れ", "死", "दर्द", "आँसू", "तन्हा", "கண்ணீர்", "சோக",
)

private val themeWords = linkedMapOf(
    "happy" to setOf("happy", "sunshine", "smile", "joy", "celebrate", "khushi"),
    "sad" to setOf("cry", "tears", "lonely", "alone", "goodbye", "broken", "dard", "tanha", "aansu"),
    "rain" to setOf("rain", "raining", "rainy", "rainfall", "storm", "thunder", "umbrella", "baarish", "barish", "barsaat", "sawan", "monsoon"),
    "night" to setOf("night", "nights", "midnight", "moon", "moonlight", "stars", "dark", "raat", "chaand", "chand"),
    "drive" to setOf("drive", "driving", "road", "highway", "car", "ride", "riding", "wheel", "fast", "speed", "run", "running", "gaadi"),
    "party" to setOf("party", "club", "dance", "dancing", "dancefloor", "tonight", "drink", "shots", "nachna", "naach"),
    "love" to setOf("love", "lover", "loving", "baby", "darling", "heart", "kiss", "pyaar", "pyar", "ishq", "mohabbat", "dil", "sanam", "jaan"),
)
private val themeScript = linkedMapOf(
    "rain" to listOf("雨", "बारिश", "மழை"),
    "night" to listOf("夜", "月", "星", "रात", "இரவு"),
    "love" to listOf("愛", "恋", "好き", "प्यार", "इश्क", "காதல்"),
    "sad" to listOf("涙", "悲し", "दर्द"),
    "happy" to listOf("幸せ", "笑顔", "खुशी"),
    "drive" to listOf("走", "道"),
    "party" to listOf("踊", "नाच"),
)

private val wordRe = Regex("[a-z']+")
private val lrcStamp = Regex("""\[\d+:\d+(?:\.\d+)?\]""")
private val latinOrSpace = Regex("""[a-z\s\p{P}\d]""")

/** What a song's metadata says about how it feels. */
class SongTraits(
    var valence: Double,
    var energy: Double,
    /** From 0, knowing nothing, to 1. */
    var confidence: Double,
    val themes: Map<String, Double>,
    val instrumental: Boolean,
    /** Its words actually said something positive or negative; genre and artist only suggest a feeling. */
    val hasSentiment: Boolean = false,
)

private fun countOf(text: String, needle: String): Int {
    var n = 0
    var at = 0
    while (true) {
        at = text.indexOf(needle, at)
        if (at == -1) return n
        n++
        at += needle.length
    }
}

/** Reads a song's traits from its lyrics, title, genre and length. Plain text work, needs no audio. */
fun analyzeSong(s: Song): SongTraits {
    var valence = 0.0
    var energy = 0.0
    var weight = 0.0
    fun vote(v: Double, e: Double, w: Double) {
        valence += v * w
        energy += e * w
        weight += w
    }

    val genre = s.genre?.lowercase()
    if (genre != null) {
        for ((k, v) in genreFeel) {
            if (genre.contains(k)) {
                vote(v.first, v.second, 1.0)
                break
            }
        }
    }
    if (s.instrumental) vote(0.0, -0.3, 0.5)

    var hasSentiment = false
    val themes = HashMap<String, Double>()
    val title = s.title.lowercase()
    val titleWords = wordRe.findAll(title).map { it.value }.toSet()
    // A word in the title says more than one anywhere in the lyrics.
    for ((k, v) in themeWords) if (titleWords.any { it in v }) themes[k] = 1.0
    for ((k, v) in themeScript) if (v.any { title.contains(it) }) themes[k] = 1.0

    val raw = s.lyrics
    if (!raw.isNullOrEmpty()) {
        val text = raw.replace(lrcStamp, " ").lowercase()
        val words = wordRe.findAll(text).map { it.value }.toList()
        var pos = 0
        var neg = 0
        val hits = HashMap<String, Int>()
        for (w in words) {
            if (w in positive) pos++
            if (w in negative) neg++
            for ((k, v) in themeWords) if (w in v) hits[k] = (hits[k] ?: 0) + 1
        }
        for (w in positiveScript) pos += countOf(text, w)
        for (w in negativeScript) neg += countOf(text, w)
        for ((k, v) in themeScript) for (w in v) hits[k] = (hits[k] ?: 0) + countOf(text, w)
        // Scripts without spaces count characters, so a word is about two of them.
        val nonLatin = text.replace(latinOrSpace, "").length
        val tokens = words.size + nonLatin / 2.0
        if (tokens > 0) {
            if (pos + neg > 0) {
                // Leans to neutral when there's little evidence, however one-sided.
                val lean = (pos - neg).toDouble() / (pos + neg + 3)
                hasSentiment = pos + neg >= 3
                vote(lean.coerceIn(-1.0, 1.0), 0.0, 1.5)
            }
            // Words per second: fast, wordy songs tend to feel more intense.
            if (s.duration_sec > 0) {
                val rate = tokens / s.duration_sec
                vote(0.0, ((rate - 1.2) / 1.2).coerceIn(-1.0, 1.0), 0.8)
            }
            for ((k, h) in hits) {
                val share = h / sqrt(tokens)
                themes[k] = min(1.0, (themes[k] ?: 0.0) + share)
            }
        }
    }

    return SongTraits(
        if (weight == 0.0) 0.0 else valence / weight,
        if (weight == 0.0) 0.0 else energy / weight,
        min(1.0, weight / 2.5),
        themes,
        s.instrumental,
        hasSentiment,
    )
}

/** Fills gaps from a song's album and artist: songs by one artist tend to feel alike. */
private fun spreadTraits(songs: List<Song>, traits: Map<String, SongTraits>) {
    for (keyOf in listOf<(Song) -> String>({ firstArtist(it.artist) }, { it.album })) {
        val sums = HashMap<String, DoubleArray>()
        for (s in songs) {
            val k = keyOf(s)
            if (k.isEmpty()) continue
            val t = traits.getValue(s.unique_id)
            val sum = sums.getOrPut(k) { DoubleArray(3) }
            sum[0] += t.valence * t.confidence
            sum[1] += t.energy * t.confidence
            sum[2] += t.confidence
        }
        for (s in songs) {
            val sum = sums[keyOf(s)] ?: continue
            if (sum[2] == 0.0) continue
            val t = traits.getValue(s.unique_id)
            val pull = 0.5
            val total = t.confidence + pull
            t.valence = (t.valence * t.confidence + sum[0] / sum[2] * pull) / total
            t.energy = (t.energy * t.confidence + sum[1] / sum[2] * pull) / total
            t.confidence = min(1.0, t.confidence + pull * min(1.0, sum[2]) * 0.5)
        }
    }
}

/** How well a song suits a mood, before personal taste. Above about 0.5 is a fit. */
fun moodFit(t: SongTraits, m: Mood): Double {
    val dv = t.valence - m.valence
    val de = t.energy - m.energy
    val spread = 0.5
    val near = exp(-(dv * dv + de * de) / (2 * spread * spread))
    var fit = near * (0.3 + 0.7 * t.confidence)
    val themed = m.theme?.let { t.themes[it] }
    // Moods that are about a feeling need words that show it.
    if (abs(m.valence) >= 0.5 && !t.hasSentiment && themed == null) fit *= 0.4
    // Singing about it is strong evidence, but only if the song also feels close.
    if (m.theme != null) fit += 1.5 * (themed ?: 0.0) * max(0.15, near)
    if (m.wantsInstrumental) fit += if (t.instrumental) 0.5 else -0.2
    return fit
}

/** Stable across devices and runs, so one library gives the same mixes on every device on the same day. */
fun stableHash(text: String): Long {
    var h = 0x811c9dc5L
    for (c in text) h = ((h xor c.code.toLong()) * 0x01000193L) and 0x7fffffffL
    return h
}

class MoodVerdict(
    val uniqueId: String,
    val artist: String,
    val mood: String,
    /** 1 for "fits this mood", -1 for "doesn't". */
    val vote: Int,
)

/** A list of songs someone made: a playlist, a Telegram topic, or their favorites. */
class TasteList(val name: String, val songIds: Set<String>)
