package io.max.paperplay.ui.app

import io.max.paperplay.ui.logic.Selection
import io.max.paperplay.ui.state.SongUi
import kotlin.test.Test
import kotlin.test.assertEquals

class RepeatRowTest {
    private val song = SongUi(id = "u1", title = "Song", artist = "A")
    private val repeat = song.copy(copyKey = "-100:7")
    private val rows = listOf(song, repeat)

    @Test
    fun pickingTheRepeatDoesNotPickTheSong() {
        val picked = Selection().toggle(repeat.rowKey)
        assertEquals(listOf(repeat), rows.filter { it.rowKey in picked })
        assertEquals(emptyList(), deletableRows(rows, picked))
    }

    @Test
    fun pickingBothDeletesOnlyTheSongRow() {
        val picked = Selection().toggle(song.rowKey).toggle(repeat.rowKey)
        assertEquals(listOf(song), deletableRows(rows, picked))
    }
}
