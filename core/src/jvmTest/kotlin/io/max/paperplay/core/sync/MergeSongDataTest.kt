package io.max.paperplay.core.sync

import io.max.paperplay.core.library.EnrichState
import io.max.paperplay.core.library.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class MergeSongDataTest {
    private fun song(artist: String = "Unknown artist", album: String = "", genre: String? = null, lyrics: String? = null, state: Long = 0) = Song(
        1, 1, "u", "r", "t", artist, album, 0, 100, "audio/mpeg", "t.mp3", 1,
        null, null, null, genre, null, null, false, false, false, state, lyrics, 0, null, false, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    private fun sync(edited: Boolean, at: Long) = SongSync("t", edited, at, false, 0, 0, 0, null)
    private fun extra(artist: String, genre: String? = null, lyrics: String? = null) = SongExtra(artist, "Album", genre, 2020, false, lyrics, "rel", null, "deezer", true)

    @Test fun emptyFieldsAreFilledEvenFromAutomaticData() {
        val m = mergeSongData(song(), sync(false, 1), sync(false, 1), extra("Artist", "Pop", "la la"))
        assertEquals("Artist", m.artist); assertEquals("Album", m.album); assertEquals("Pop", m.genre)
        assertEquals("la la", m.lyrics); assertEquals(EnrichState.DONE, m.enrich_state)
    }

    @Test fun automaticNeverOverwritesManual() {
        val m = mergeSongData(song(artist = "Mine", genre = "Rock"), sync(true, 5), sync(false, 9), extra("Theirs", "Pop"))
        assertEquals("Mine", m.artist); assertEquals("Rock", m.genre)
    }

    @Test fun manualBeatsAutomaticOnTheOtherSide() {
        val m = mergeSongData(song(artist = "Auto", genre = "Rock"), sync(false, 9), sync(true, 1), extra("Hand", "Jazz"))
        assertEquals("Hand", m.artist); assertEquals("Jazz", m.genre)
    }

    @Test fun lyricsConvergeAndManualWins() {
        // Nothing tells the two apart: both devices pick the same text, whichever side they are.
        val a = mergeSongData(song(lyrics = "aaa"), sync(false, 1), sync(false, 1), extra("A", lyrics = "bbb")).lyrics
        val b = mergeSongData(song(lyrics = "bbb"), sync(false, 1), sync(false, 1), extra("A", lyrics = "aaa")).lyrics
        assertEquals(a, b)
        // A text chosen by hand is never replaced by an automatic one, and replaces an automatic one.
        assertEquals("mine", mergeSongData(song(lyrics = "mine"), sync(false, 1), sync(false, 1), extra("A", lyrics = "theirs"), LyricsMeta(true, 5)).lyrics)
        assertEquals("theirs", mergeSongData(song(lyrics = "mine"), sync(false, 1), sync(false, 1), extra("A", lyrics = "theirs").copy(lyrManual = true, lyrAt = 5)).lyrics)
        // Timed lyrics beat plain ones.
        assertEquals("[00:01.00]x", mergeSongData(song(lyrics = "plain long text here"), sync(false, 1), sync(false, 1), extra("A", lyrics = "[00:01.00]x")).lyrics)
    }
}
