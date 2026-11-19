package io.max.paperplay.core.recs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class RecommenderTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    /** A sound vector near one of two styles: "a" loud and bright, "b" quiet and dark. */
    private fun sound(style: Char, rnd: Random): FloatArray = FloatArray(45) { i ->
        val base = if (style == 'a') (i % 7 + 3).toFloat() else -(i % 5 + 2).toFloat()
        base + rnd.nextFloat() * 0.6f
    }.also { it[14] = if (style == 'a') 140f else 80f }

    private class Lib(val songs: List<RecSong>, val vectors: Map<String, FloatArray>)

    private fun library(rnd: Random): Lib {
        val songs = ArrayList<RecSong>()
        val vec = HashMap<String, FloatArray>()
        for (style in listOf('a', 'b')) for (i in 0 until 30) {
            // Artists are spread across both styles, so only the sound tells the styles apart.
            val id = "$style$i"
            songs += RecSong(id, "artist${i % 6}", null, false)
            vec[id] = sound(style, rnd)
        }
        return Lib(songs, vec)
    }

    private fun ev(uid: String, daysAgo: Double, share: Double, hour: Int = 20, prev: String? = null, dur: Long = 200_000) =
        PlayEvent(uid, now - (daysAgo * day).toLong(), hour, (dur * share).toLong(), dur, prev)

    @Test
    fun songs_that_sound_like_finished_ones_rank_above_ones_that_sound_like_skips() {
        val lib = library(Random(1))
        val events = (0 until 20).flatMap { i -> listOf(ev("a$i", 30.0 - i, 1.0), ev("b$i", 30.0 - i, 0.05)) }
        val r = Recommender.build(lib.songs, lib.vectors, events, now)
        val unseenA = (20 until 30).map { r.predict("a$it", 20) }.average()
        val unseenB = (20 until 30).map { r.predict("b$it", 20) }.average()
        assertTrue(unseenA > unseenB + 0.1, "a $unseenA vs b $unseenB")
    }

    @Test
    fun an_early_skip_counts_against_more_than_a_late_one() {
        val lib = library(Random(2))
        val r = Recommender.build(lib.songs, emptyMap(), listOf(ev("a1", 1.0, 0.05), ev("a2", 1.0, 0.6)), now)
        assertTrue(r.affinity("a1") < r.affinity("a2"), "${r.affinity("a1")} vs ${r.affinity("a2")}")
        assertTrue(r.affinity("a1") < 0)
    }

    @Test
    fun old_listens_fade() {
        val lib = library(Random(3))
        val r = Recommender.build(lib.songs, emptyMap(), listOf(ev("a1", 1.0, 1.0), ev("a2", 400.0, 1.0)), now)
        assertTrue(r.affinity("a1") > r.affinity("a2") + 0.1)
    }

    @Test
    fun what_was_liked_at_this_hour_wins_at_this_hour() {
        val lib = library(Random(4))
        // Style a heard through late at night, style b in the morning; both liked equally overall.
        val events = (0 until 20).flatMap { i -> listOf(ev("a$i", 40.0 - i, 1.0, hour = 23), ev("b$i", 40.0 - i, 1.0, hour = 8)) }
        val r = Recommender.build(lib.songs, lib.vectors, events, now)
        val nightA = (20 until 30).map { r.predict("a$it", 23) }.average()
        val nightB = (20 until 30).map { r.predict("b$it", 23) }.average()
        val dayA = (20 until 30).map { r.predict("a$it", 8) }.average()
        val dayB = (20 until 30).map { r.predict("b$it", 8) }.average()
        assertTrue(nightA > nightB, "night a $nightA b $nightB")
        assertTrue(dayB > dayA, "day a $dayA b $dayB")
    }

    @Test
    fun a_song_that_usually_follows_ranks_higher_after_its_lead() {
        val lib = library(Random(5))
        val events = ArrayList<PlayEvent>()
        for (i in 0 until 8) {
            events += ev("a0", 20.0 - i, 1.0)
            events += ev("a1", 20.0 - i - 0.001, 1.0, prev = "a0")
            events += ev("a2", 20.0 - i - 0.5, 1.0)
        }
        val r = Recommender.build(lib.songs, emptyMap(), events, now)
        assertTrue(r.predict("a1", 20, after = "a0") > r.predict("a2", 20, after = "a0"))
        assertTrue(r.similar("a0").first().first == "a1")
    }

    @Test
    fun replay_learns_better_than_a_coin() {
        // A listener with a clear taste: style a heard through 90% of the time, style b skipped 90% of the time.
        val lib = library(Random(6))
        val rnd = Random(7)
        val events = (0 until 600).map { i ->
            val style = if (rnd.nextBoolean()) 'a' else 'b'
            val id = "$style${rnd.nextInt(30)}"
            val through = if (style == 'a') rnd.nextDouble() < 0.9 else rnd.nextDouble() < 0.1
            ev(id, 600.0 - i, if (through) 1.0 else 0.05)
        }
        val r = Recommender.build(lib.songs, lib.vectors, events, now)
        assertTrue(r.replayLogLoss < 0.5, "log loss ${r.replayLogLoss} (a coin is 0.69)")
    }

    @Test
    fun nothing_known_is_safe() {
        val r = Recommender.build(emptyList(), emptyMap(), emptyList(), now)
        assertTrue(r.rank(12).isEmpty())
        assertTrue(r.replayLogLoss.isNaN())
    }
}
