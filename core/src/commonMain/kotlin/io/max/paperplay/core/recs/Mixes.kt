package io.max.paperplay.core.recs

import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/** A generated mix on Home. [id] is stable for one kind of mix, so its page and saved copy find it again. */
class DailyMix(val id: String, val title: String, val songs: List<Song>)

/**
 * The mixes on Home, from the [Recommender]:
 * - one mix per sound the person likes: liked songs grouped by how they sound (k-means on the audio vectors), each filled with
 *   the songs the model rates highest among those that sound like that group;
 * - "Right now": the best songs for this hour;
 * - "New to you": songs never heard here that the model expects to land.
 * Songs heard in the last two days rest, an artist appears at most three times in a mix, and each mix is ordered so that tempo,
 * key and loudness move smoothly from one song to the next.
 */
object RecMixes {
    private const val LENGTH = 30
    private const val MIN_LENGTH = 8
    private const val PER_ARTIST = 3
    private const val REST_MS = 2 * 86_400_000L

    suspend fun build(
        songs: List<Song>,
        vectors: Map<String, FloatArray>,
        events: List<PlayEvent>,
        lists: List<TasteList>,
        now: Moment,
    ): List<DailyMix> = withContext(Dispatchers.Default) {
        if (songs.isEmpty()) return@withContext emptyList()
        val inLists = HashMap<String, Int>()
        for (l in lists) for (id in l.songIds) inLists[id] = (inLists[id] ?: 0) + 1
        val rec = Recommender.build(
            songs.map { RecSong(it.unique_id, it.artist, it.genre, it.favorite, inLists[it.unique_id] ?: 0) }, vectors, events, now.epochMs,
        )
        val byId = songs.associateBy { it.unique_id }
        val lastHeard = HashMap<String, Long>()
        for (e in events) lastHeard[e.uid] = maxOf(lastHeard[e.uid] ?: 0L, e.at)
        val resting = byId.keys.filter { now.epochMs - (lastHeard[it] ?: byId[it]!!.last_played_at ?: 0L) < REST_MS }.toSet()
        val rejected = events.filter { it.heardMs == 0L }.map { it.uid }.toSet()
        val score = rec.rank(now.hour, exclude = rejected).toMap()
        val norm = Recommender.normalize(vectors.filterKeys { it in byId })
        val used = HashSet<String>()
        val out = ArrayList<DailyMix>()

        fun pick(candidates: List<Pair<String, Double>>): List<Song> {
            val perArtist = HashMap<String, Int>()
            val chosen = ArrayList<Song>()
            for ((id, _) in candidates.sortedByDescending { (id, s) -> s - if (id in used) 0.15 else 0.0 }) {
                if (id in resting || id in rejected) continue
                val s = byId[id] ?: continue
                val a = firstArtist(s.artist).lowercase()
                if ((perArtist[a] ?: 0) >= PER_ARTIST) continue
                perArtist[a] = (perArtist[a] ?: 0) + 1
                chosen += s
                if (chosen.size >= LENGTH) break
            }
            return chosen
        }

        fun add(id: String, title: String, list: List<Song>) {
            if (list.size < MIN_LENGTH) return
            out += DailyMix(id, title, flow(list, vectors))
            list.forEach { used += it.unique_id }
        }

        // Liked songs: strong affinity, else favorites, else the most played, so a new install still gets sound mixes.
        val liked = byId.keys.filter { it in norm && rec.affinity(it) > 0.2 }.ifEmpty {
            songs.filter { it.unique_id in norm && (it.favorite || it.plays > 0) }.map { it.unique_id }
        }
        val k = (liked.size / 25).coerceIn(if (liked.size >= 16) 2 else 0, 5)
        if (k > 0) {
            val groups = kMeans(liked.map { norm[it]!! }, k)
            val size = IntArray(groups.size)
            for (id in liked) size[groupOf(norm[id]!!, groups)]++
            val names = HashMap<String, Int>()
            for (g in groups.indices.sortedByDescending { size[it] }) {
                val candidates = norm.map { (id, v) -> id to (Recommender.cosine(v, groups[g]) + (score[id] ?: 0.0)) }
                val base = nameFor(groups[g])
                val n = (names[base] ?: 0) + 1
                names[base] = n
                add("sound$g", if (n > 1) "$base $n" else base, pick(candidates))
            }
        }
        add("now", titleForHour(now.hour), pick(score.toList()))
        val heard = lastHeard.keys + songs.filter { it.plays > 0 || it.last_played_at != null }.map { it.unique_id }
        add("new", "New to you", pick(score.filterKeys { it !in heard }.toList()))
        out
    }

    private fun titleForHour(h: Int) = when (h) {
        in 5..10 -> "Right now: morning"
        in 11..16 -> "Right now: afternoon"
        in 17..21 -> "Right now: evening"
        else -> "Right now: late night"
    }

    /** A short name from how a group sounds next to the rest of the library (centre values are z-scores). */
    private fun nameFor(c: DoubleArray): String {
        val loud = c[0] + c[16] // loudness and onsets
        val tempo = c[14]
        val bass = c[9]
        val bright = c[3] + c[5]
        val words = buildList {
            when {
                tempo > 0.15 && loud > 0.2 -> add("Upbeat")
                tempo < -0.15 && loud < -0.2 -> add("Slow and soft")
                loud > 0.25 -> add("Loud")
                loud < -0.25 -> add("Mellow")
            }
            when {
                bass > 0.2 -> add("bass heavy")
                bright > 0.3 -> add("bright")
                bright < -0.3 -> add("warm")
            }
        }
        return if (words.isEmpty()) "Your sound" else words.joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    private fun groupOf(v: DoubleArray, centres: List<DoubleArray>): Int =
        centres.indices.maxByOrNull { Recommender.cosine(v, centres[it]) } ?: 0

    /** Spherical k-means with a fixed, spread-out start (farthest points), so the same library gives the same groups. */
    internal fun kMeans(points: List<DoubleArray>, k: Int, rounds: Int = 12): List<DoubleArray> {
        if (points.isEmpty() || k <= 0) return emptyList()
        val centres = arrayListOf(points[0].copyOf())
        while (centres.size < min(k, points.size)) {
            centres += points.maxByOrNull { p -> -centres.maxOf { Recommender.cosine(p, it) } }!!.copyOf()
        }
        repeat(rounds) {
            val sums = List(centres.size) { DoubleArray(points[0].size) }
            for (p in points) {
                val g = groupOf(p, centres)
                for (i in p.indices) sums[g][i] += p[i]
            }
            for (g in centres.indices) {
                val n = points.count { groupOf(it, centres) == g }
                if (n > 0) for (i in sums[g].indices) centres[g][i] = sums[g][i] / n
            }
        }
        return centres
    }

    /**
     * Orders a mix like a DJ would: starts from the calmest song, then each next song is the closest in tempo, key (circle of fifths)
     * and loudness to the one before. Songs without a vector go at the end in their given order.
     */
    internal fun flow(list: List<Song>, vectors: Map<String, FloatArray>): List<Song> {
        val (known, unknown) = list.partition { vectors[it.unique_id]?.size?.let { n -> n >= 45 } == true }
        if (known.size < 3) return list
        val left = known.toMutableList()
        fun v(s: Song) = vectors[s.unique_id]!!
        fun energy(s: Song) = v(s)[0] + v(s)[16] * 2f
        val out = ArrayList<Song>()
        var cur = left.minByOrNull(::energy)!!
        left.remove(cur); out += cur
        while (left.isNotEmpty()) {
            val a = v(cur)
            val next = left.minByOrNull { s -> step(a, v(s)) }!!
            left.remove(next); out += next; cur = next
        }
        return out + unknown
    }

    /** How jarring it is to go from a song to the next: tempo ratio, key distance on the circle of fifths, loudness. */
    internal fun step(a: FloatArray, b: FloatArray): Double {
        val ta = a[14].toDouble().coerceAtLeast(40.0)
        val tb = b[14].toDouble().coerceAtLeast(40.0)
        val r = maxOf(ta, tb) / minOf(ta, tb)
        // Half or double time mixes fine.
        val tempo = minOf(abs(r - 1.0), abs(r / 2.0 - 1.0) + 0.05)
        fun fifths(pc: Int) = (pc * 7) % 12
        // Relative minor sits three semitones below its major; compare both on the major's place.
        fun place(v: FloatArray) = fifths(((v[17].toInt() + if (v[18] < 0.5f) 3 else 0) % 12 + 12) % 12)
        val d = abs(place(a) - place(b)).let { min(it, 12 - it) }
        val loud = abs(a[0] - b[0]) / 6.0
        return tempo * 4.0 + d / 6.0 + loud
    }
}
