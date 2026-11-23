package io.max.paperplay.core.app

import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.asSong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitNamesTest {
    private var next = 1L

    /** A song whose tags are the file name split at its first " - ", as the scanner stores an untagged file. */
    private fun named(file: String, chat: Long = -1): io.max.paperplay.core.library.Song {
        val (a, t) = parseFileNameFirst(file)
        return tagged(a, t, file, chat)
    }

    private fun tagged(artist: String, title: String, file: String, chat: Long = -1) =
        ChatPost(chat, next, "u$next", title, artist, 100, 1, "audio/flac", file, next++).asSong()
            .copy(chat_id = chat, title = title, artist = artist, file_name = file)

    private fun fixed(songs: List<io.max.paperplay.core.library.Song>) =
        repairSplitNames(songs).associate { it.file_name to (it.artist to it.title) }

    @Test
    fun songFirstNamesAreTurnedRoundFromTheLibrary() {
        val songs = listOf(
            named("Mazaak - Anuv Jain.flac"), named("Gul - Anuv Jain.flac"),
            tagged("Anuv Jain", "Arz Kiya Hai", "arz.flac"), // Anuv Jain is tagged on another song
            named("Rehna Duur - Akash Kaushal, Prateeksha Srivastava.flac"), // a list of artists
            named("Hukum Reloaded - Anirudh Ravichander, Super Subu.m4a"), // Anirudh is tagged on another song
            tagged("Anirudh Ravichander, Ravi G", "Adiye En Poonthene", "adiye.flac"),
        )
        val f = fixed(songs)
        assertEquals("Anuv Jain" to "Mazaak", f["Mazaak - Anuv Jain.flac"])
        assertEquals("Anuv Jain" to "Gul", f["Gul - Anuv Jain.flac"])
        assertEquals("Akash Kaushal, Prateeksha Srivastava" to "Rehna Duur", f["Rehna Duur - Akash Kaushal, Prateeksha Srivastava.flac"])
        assertEquals("Anirudh Ravichander, Super Subu" to "Hukum Reloaded", f["Hukum Reloaded - Anirudh Ravichander, Super Subu.m4a"])
    }

    @Test
    fun noEvidenceAndFalseListsStay() {
        val songs = listOf(
            named("Mazaak - Anuv Jain.flac"), named("Gul - Anuv Jain.flac"),
            named("Theek Hai - Rahul Shah.flac"), // no evidence either way: left to the online lookup
            named("02 - Korn - Another Brick in the Wall, Pt. 1, 2, 3.flac"), // "Pt. 1, 2, 3" is not a list of artists
            named("Mystery Skulls - Hellbent (feat. Snowblood).flac"), // a feat. in brackets belongs to the song
            named("Xavier Wulf - Tokyo Drift.mp3"), named("PedroDJDaddy - Tokyo Drift.flac"), // covers share a song name
        )
        val f = fixed(songs)
        assertTrue("Theek Hai - Rahul Shah.flac" !in f)
        assertTrue("02 - Korn - Another Brick in the Wall, Pt. 1, 2, 3.flac" !in f)
        assertTrue("Mystery Skulls - Hellbent (feat. Snowblood).flac" !in f)
        assertTrue("Xavier Wulf - Tokyo Drift.mp3" !in f)
    }

    @Test
    fun aNumberThatIsPartOfTheNameStays() {
        assertEquals("1-800 - bbno\$, Ironmouse".substringBefore(" - "), parseFileNameFirst("1-800 - bbno\$, Ironmouse.flac").first)
        assertEquals("Korn", parseFileNameFirst("02 - Korn - Song.flac").first)
    }

    @Test
    fun artistFirstNamesStay() {
        val songs = listOf(
            named("Anuv Jain - Mazaak.flac"), named("Anuv Jain - Gul.flac"),
            named("Akash Kaushal, Prateeksha Srivastava - Rehna Duur.flac"),
            named("Rahul Shah - Theek Hai.flac"),
        )
        assertTrue(fixed(songs).isEmpty())
    }

    @Test
    fun runningAgainChangesNothing() {
        val songs = listOf(named("Mazaak - Anuv Jain.flac"), named("Gul - Anuv Jain.flac"), named("Theek Hai - Rahul Shah.flac"),
            named("Rehna Duur - Akash Kaushal, Prateeksha Srivastava.flac"))
        val once = repairSplitNames(songs).associateBy { it.file_name }
        val after = songs.map { once[it.file_name] ?: it }
        assertTrue(repairSplitNames(after).isEmpty())
    }

    @Test
    fun handEditedSongsAreLeftAlone() {
        val songs = listOf(named("Mazaak - Anuv Jain.flac").copy(user_edited = true), named("Gul - Anuv Jain.flac"))
        assertTrue("Mazaak - Anuv Jain.flac" !in fixed(songs))
    }
}
