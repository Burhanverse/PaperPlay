package io.max.paperplay.core.recs

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/** What the recommender needs to know about a song besides how it sounds. */
class RecSong(val uid: String, val artist: String, val genre: String?, val favorite: Boolean, val listCount: Int = 0)

/**
 * A per-person model of what they will enjoy, learned on the device from their listening.
 *
 * Signals: how much of each song was heard (an early skip says much more than a late one, and old listens fade), favorites and
 * playlists, the same for each artist and genre, how close a song sounds to the ones they like (audio vectors from the Rust
 * engine), what they like at this hour, and which song tends to follow which.
 *
 * These go into a small logistic model that is trained by replaying the listening history in order: each listen is first predicted
 * from what was known before it, then learned from. That keeps the weights honest (no listen grades itself) and gives
 * [replayLogLoss], the measure later stages are compared on.
 */
class Recommender private constructor(
    private val songs: Map<String, RecSong>,
    private val state: State,
    private val weights: DoubleArray,
    /** Mean log loss of the predictions made while replaying, each before its listen was learned. Lower is better. */
    val replayLogLoss: Double,
    /** Listens that took part in training (clear finishes and skips). */
    val trainedOn: Int,
) {
    /** How likely [uid] is to be enjoyed (heard through) now, 0 to 1. [hour] is the local hour; [after] the song just played, if any. */
    fun predict(uid: String, hour: Int, after: String? = null): Double = sigmoid(dot(weights, state.inputs(uid, hour, after)))

    /** [predict] for every song, best first. */
    fun rank(hour: Int, after: String? = null, exclude: Set<String> = emptySet()): List<Pair<String, Double>> =
        songs.keys.asSequence().filter { it !in exclude }.map { it to predict(it, hour, after) }.sortedByDescending { it.second }.toList()

    /** Songs that sound like [uid] or tend to follow it, closest first. */
    fun similar(uid: String, k: Int = 20): List<Pair<String, Double>> {
        val v = state.norm[uid]
        val base = songs[uid]
        return songs.keys.asSequence().filter { it != uid }.map { o ->
            val sound = if (v != null) state.norm[o]?.let { cosine(v, it) } ?: 0.0 else 0.0
            val pair = state.coListen(uid, o)
            val artist = if (base != null && firstArtist(base.artist).equals(firstArtist(songs[o]!!.artist), true)) 0.15 else 0.0
            o to (sound + pair + artist)
        }.sortedByDescending { it.second }.take(k).toList()
    }

    /** The learned weights by input name, for the debug view and tests. */
    fun weightsByName(): Map<String, Double> = INPUTS.indices.associate { INPUTS[it] to weights[it] }

    /** The person's like score for [uid] from their own listens and lists alone, -1 to 1 (0 when unknown). */
    fun affinity(uid: String): Double = state.songAffinity(uid)

    companion object {
        val INPUTS = listOf("bias", "song", "artist", "genre", "sound", "hour", "follows", "familiar")
        private val PRIOR = doubleArrayOf(0.0, 1.5, 1.0, 0.4, 1.2, 0.6, 0.8, 0.2)
        private const val RATE = 0.1
        private const val L2 = 1e-4
        private const val HALF_LIFE_DAYS = 90.0

        fun build(songs: List<RecSong>, vectors: Map<String, FloatArray>, events: List<PlayEvent>, now: Long): Recommender {
            val byId = songs.associateBy { it.uid }
            val state = State(byId, normalize(vectors.filterKeys { it in byId }), now)
            for (s in songs) state.addExplicit(s)
            val w = PRIOR.copyOf()
            var loss = 0.0
            var n = 0
            for (e in events.sortedBy { it.at }) {
                if (e.uid !in byId) continue
                val label = labelOf(e)
                state.clock = e.at
                if (label != null) {
                    val x = state.inputs(e.uid, e.hour, e.prev)
                    val p = sigmoid(dot(w, x)).coerceIn(1e-6, 1 - 1e-6)
                    loss += -(label * ln(p) + (1 - label) * ln(1 - p))
                    n++
                    val g = p - label
                    for (i in w.indices) w[i] -= RATE * (g * x[i] + if (i == 0) 0.0 else L2 * w[i])
                }
                state.learn(e)
            }
            state.clock = now
            return Recommender(byId, state, w, if (n > 0) loss / n else Double.NaN, n)
        }

        /** 1 for a song heard through, 0 for a skip, null for something in between (not clear enough to learn from). */
        internal fun labelOf(e: PlayEvent): Double? {
            val r = heardShare(e)
            return when {
                r >= 0.8 -> 1.0
                r < 0.4 || e.heardMs < 30_000 -> 0.0
                else -> null
            }
        }

        /** -1 (skipped at once) to 1 (heard through). */
        internal fun valueOf(e: PlayEvent): Double {
            val r = heardShare(e)
            return when {
                r >= 0.8 -> 1.0
                e.heardMs < 30_000 -> -1.0
                r < 0.4 -> -0.5
                else -> 0.2
            }
        }

        private fun heardShare(e: PlayEvent): Double =
            if (e.durMs > 0) (e.heardMs.toDouble() / e.durMs).coerceIn(0.0, 1.0) else if (e.heardMs >= 120_000) 1.0 else 0.0

        /**
         * Z-scores each feature across the library, then weighs each group (loudness, spectrum, bands, rhythm, harmony, timbre) equally,
         * so thirteen timbre numbers do not drown out one tempo. The key's pitch class is left out: chroma carries harmony.
         */
        internal fun normalize(raw: Map<String, FloatArray>): Map<String, DoubleArray> {
            val usable = raw.filterValues { it.size >= 45 }
            if (usable.isEmpty()) return emptyMap()
            val dims = 45
            fun value(v: FloatArray, i: Int): Double = if (i == 14) log2(max(v[i].toDouble(), 40.0)) else v[i].toDouble()
            val mean = DoubleArray(dims)
            val sd = DoubleArray(dims)
            for (v in usable.values) for (i in 0 until dims) mean[i] += value(v, i)
            for (i in 0 until dims) mean[i] /= usable.size
            for (v in usable.values) for (i in 0 until dims) { val d = value(v, i) - mean[i]; sd[i] += d * d }
            for (i in 0 until dims) sd[i] = sqrt(sd[i] / usable.size).takeIf { it > 1e-9 } ?: 1.0
            val groups = listOf(0..2, 3..8, 9..13, 14..16, 18..31, 32..44)
            val weight = DoubleArray(dims)
            for (g in groups) for (i in g) weight[i] = 1.0 / sqrt((g.last - g.first + 1).toDouble())
            return usable.mapValues { (_, v) -> DoubleArray(dims) { i -> (value(v, i) - mean[i]) / sd[i] * weight[i] } }
        }

        internal fun cosine(a: DoubleArray, b: DoubleArray): Double {
            var d = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na <= 0 || nb <= 0) 0.0 else d / sqrt(na * nb)
        }

        private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
        private fun dot(w: DoubleArray, x: DoubleArray): Double { var s = 0.0; for (i in w.indices) s += w[i] * x[i]; return s }
    }

    /** Everything learned so far from the listens replayed. */
    private class State(val songs: Map<String, RecSong>, val norm: Map<String, DoubleArray>, val now: Long) {
        /** The moment predictions are made for: each listen's own time while replaying, then now. */
        var clock: Long = now

        /** Decayed sum of listen values and of their weights, per song, artist and genre. */
        private val song = HashMap<String, DoubleArray>()
        private val artist = HashMap<String, DoubleArray>()
        private val genre = HashMap<String, DoubleArray>()
        private val explicit = HashMap<String, Double>()
        private val heard = HashMap<String, Int>()
        /** Liked songs with their like strength, for "sounds like what you like". */
        private val liked = HashMap<String, Double>()
        /** Per local hour: summed sound of songs heard through. */
        private val hourSum = Array(24) { DoubleArray(45) }
        private val allSum = DoubleArray(45)
        /** prev -> next -> decayed count of heard-through follow-ups. */
        private val follows = HashMap<String, HashMap<String, Double>>()

        fun addExplicit(s: RecSong) {
            val e = (if (s.favorite) 0.6 else 0.0) + 0.3 * min(s.listCount, 2)
            if (e > 0) {
                explicit[s.uid] = e
                liked[s.uid] = e
            }
        }

        private fun decay(at: Long): Double = exp(-ln(2.0) * max(0.0, (now - at) / 86_400_000.0) / HALF_LIFE_DAYS)

        private fun add(map: HashMap<String, DoubleArray>, key: String, v: Double, w: Double) {
            val a = map.getOrPut(key) { DoubleArray(2) }
            a[0] += v * w
            a[1] += w
        }

        /** Smoothed mean towards 0 (unknown) with a prior of two listens heard at [clock] (weights are relative to [now]). */
        private fun mean(map: HashMap<String, DoubleArray>, key: String?): Double {
            val a = key?.let { map[it] } ?: return 0.0
            return a[0] / (a[1] + 2.0 * decay(clock))
        }

        fun songAffinity(uid: String): Double = tanh(mean(song, uid) * 1.5 + (explicit[uid] ?: 0.0))

        fun learn(e: PlayEvent) {
            val s = songs[e.uid] ?: return
            val v = valueOf(e)
            val w = decay(e.at)
            add(song, s.uid, v, w)
            add(artist, firstArtist(s.artist).lowercase(), v, w)
            s.genre?.lowercase()?.let { add(genre, it, v, w) }
            heard[s.uid] = (heard[s.uid] ?: 0) + 1
            val aff = songAffinity(s.uid)
            if (aff > 0.25) liked[s.uid] = aff else liked.remove(s.uid)
            val x = norm[s.uid]
            if (x != null && v >= 1.0) {
                for (i in x.indices) { hourSum[e.hour][i] += x[i] * w; allSum[i] += x[i] * w }
            }
            if (e.prev != null && v > 0) {
                val m = follows.getOrPut(e.prev) { HashMap() }
                m[s.uid] = (m[s.uid] ?: 0.0) + w * v
            }
        }

        /** How strongly [b] follows [a] in the listening history, 0 to about 1. */
        fun coListen(a: String, b: String): Double {
            val n = follows[a]?.get(b) ?: 0.0
            val back = follows[b]?.get(a) ?: 0.0
            return tanh((n + 0.5 * back) / 2.0)
        }

        /** Mean of the five best similarities to liked songs, each scaled by how much it is liked. */
        private fun soundsLiked(uid: String): Double {
            val x = norm[uid] ?: return 0.0
            val top = DoubleArray(5) { -1.0 }
            var found = 0
            for ((o, a) in liked) {
                if (o == uid) continue
                val y = norm[o] ?: continue
                val s = cosine(x, y) * a
                found++
                var i = 4
                if (s <= top[4]) continue
                while (i > 0 && top[i - 1] < s) { top[i] = top[i - 1]; i-- }
                top[i] = s
            }
            if (found == 0) return 0.0
            val k = min(found, 5)
            return (0 until k).sumOf { top[it] } / k
        }

        /** How much more [uid] sounds like what is heard through around [hour] than like what is heard through at any hour. */
        private fun hourFit(uid: String, hour: Int): Double {
            val x = norm[uid] ?: return 0.0
            val local = DoubleArray(45)
            for (h in hour - 1..hour + 1) { val a = hourSum[(h + 24) % 24]; for (i in local.indices) local[i] += a[i] }
            if (local.all { abs(it) < 1e-12 } || allSum.all { abs(it) < 1e-12 }) return 0.0
            return cosine(x, local) - cosine(x, allSum)
        }

        fun inputs(uid: String, hour: Int, prev: String?): DoubleArray {
            val s = songs[uid]
            return doubleArrayOf(
                1.0,
                songAffinity(uid),
                tanh(mean(artist, s?.artist?.let { firstArtist(it).lowercase() }) * 1.5),
                tanh(mean(genre, s?.genre?.lowercase()) * 1.5),
                soundsLiked(uid),
                hourFit(uid, hour),
                if (prev != null) coListen(prev, uid) else 0.0,
                ln(1.0 + (heard[uid] ?: 0)) / 3.0,
            )
        }
    }
}
