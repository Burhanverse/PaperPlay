package io.max.paperplay.core.dupes

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

    @Test
    fun theLastPostIsNoDuplicateAnyMore() = assertNull(group.without(setOf(-100L to 1L, -100L to 3L)))
}
