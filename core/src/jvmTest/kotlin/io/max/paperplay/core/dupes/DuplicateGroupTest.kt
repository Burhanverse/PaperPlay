package io.max.paperplay.core.dupes

import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.asSong
import io.max.paperplay.core.library.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DuplicateGroupTest {
    private fun post(id: Long) = DuplicatePost(-100, id, "f$id.flac", 10, null, true, "FLAC", id)
    private val group = DuplicateGroup(DuplicateKind.SameFile, "T", "A", post(1), listOf(post(2), post(3)), setOf(2L, 3L))

    @Test
    fun aDeletedKeeperIsReplacedByALivePost() {
        val g = group.without(setOf(-100L to 1L))!!
        assertEquals(2L, g.keep.messageId)
        assertEquals(listOf(3L), g.others.map { it.messageId })
    }

    private fun song(id: Long, mb: Int, depth: Long? = null, rate: Long? = null, ext: String = "flac", title: String = "Memories", sec: Int = 189, topic: Long = 5) =
        ChatPost(-100, id, "u$id", title, "Maroon 5", sec, mb * 1_000_000L, "audio/$ext", "f$id.$ext", id).asSong()
            .copy(topic_id = topic, bit_depth = depth, sample_rate = rate)

    private fun only(vararg s: Song) = findDuplicates(s.toList(), emptyList(), DefaultSongRules).single()

    /** Copies whose length was never read: found by their size, and only by it. */
    @Test
    fun copiesWithoutALengthAreFoundBySize() {
        assertEquals(3, only(song(1, 15, sec = 107), song(2, 15, sec = 0), song(3, 15, sec = 0)).let { 1 + it.others.size })
        assertEquals(2, only(song(1, 15, sec = 0), song(2, 15, sec = 0)).let { 1 + it.others.size })
        // Another size and no length: nothing tells it is the same recording.
        assertEquals(emptyList(), findDuplicates(listOf(song(1, 15, sec = 107), song(2, 30, sec = 0)), emptyList(), DefaultSongRules))
    }

    @Test
    fun aSmallerLosslessFileIsAnotherReleaseAndIsNotTicked() {
        // Not measured, or measured alike: a smaller FLAC says nothing.
        assertEquals(emptySet(), only(song(1, 40), song(2, 20)).suggested)
        assertEquals(emptySet(), only(song(1, 40, 16, 44100), song(2, 20, 16, 44100)).suggested)
        // Less depth at the same rate is worse; more depth at a lower rate is a matter of taste.
        assertEquals(setOf(2L), only(song(1, 40, 24, 44100), song(2, 20, 16, 44100)).suggested)
        assertEquals(emptySet(), only(song(1, 40, 24, 44100), song(2, 38, 16, 96000, topic = 6)).suggested)
        // The plain cases stay: lossy beside lossless, a smaller MP3, a re-upload in the same topic.
        assertEquals(setOf(2L), only(song(1, 40), song(2, 8, ext = "mp3")).suggested)
        assertEquals(setOf(2L), only(song(1, 8, ext = "mp3"), song(2, 4, ext = "mp3")).suggested)
        assertEquals(setOf(2L), only(song(1, 40), song(2, 39)).suggested)
    }

    @Test
    fun aFileNobodyPlayedYetIsNotWorseForThat() {
        val g = only(song(1, 25, 16, 44100), song(2, 80))
        assertEquals(2L, g.keep.messageId)
        assertEquals(emptySet(), g.suggested)
        // A played small Opus file against a bigger MP3 that was not played.
        assertEquals(setOf(1L), only(song(1, 3, 16, 48000, "opus"), song(2, 9, ext = "mp3")).suggested)
    }

    @Test
    fun otherVersionsAreNotTicked() {
        assertEquals(emptySet(), only(song(1, 40), song(2, 8, ext = "mp3", title = "Memories (Remastered 2011)")).suggested)
        assertEquals(emptySet(), only(song(1, 40), song(2, 8, ext = "mp3", title = "Memories (Clean)")).suggested)
        // Three seconds apart each, six from first to last.
        val chain = only(song(1, 40, sec = 186), song(2, 8, ext = "mp3", sec = 189), song(3, 7, ext = "mp3", sec = 192))
        assertEquals(setOf(2L), chain.suggested)
    }

    @Test
    fun theLastPostIsNoDuplicateAnyMore() = assertNull(group.without(setOf(-100L to 1L, -100L to 3L)))
}
