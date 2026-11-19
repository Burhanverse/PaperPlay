package io.max.paperplay.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongSearchTest {
    private fun score(q: String, title: String, artist: String = "", album: String = "", plays: Int = 0) =
        songSearchScore(SearchQuery(q), title, artist, album, null, null, plays = plays)

    @Test fun accentsAreIgnoredBothWays() {
        assertTrue(score("beyonce", "Halo", "Beyoncé") > 0)
        assertTrue(score("beyoncé", "Halo", "Beyonce") > 0)
    }

    @Test fun titleByArtistFindsTheSong() {
        assertTrue(score("contact by daft punk", "Contact", "Daft Punk") > 0)
        assertTrue(score("stand by me", "Stand By Me", "Ben E. King") > 0)
        assertEquals(0, score("contact by someone else", "Contact", "Daft Punk"))
    }

    @Test fun typosStillFindTheSong() {
        assertTrue(score("wekend", "Hymn for the Weekend", "Coldplay") > 0)
        assertTrue(score("weeknd", "Starboy", "The Weeknd") > 0)
        assertTrue(score("coldplya", "Yellow", "Coldplay") > 0)
    }

    @Test fun shortWordsAreNotFuzzy() {
        assertEquals(0, score("cat", "Cut", "Someone"))
        assertEquals(0, score("xyzzy", "Cut", "Someone"))
    }

    @Test fun wrongFirstLetterIsNotATypo() {
        assertEquals(0, score("zeeknd", "Starboy", "The Weeknd"))
    }

    @Test fun everyWordMustMatch() {
        assertTrue(score("hymn weekend", "Hymn for the Weekend", "Coldplay") > 0)
        assertEquals(0, score("hymn nothing", "Hymn for the Weekend", "Coldplay"))
    }

    @Test fun partOfAWordCountsButRanksBelowAWordStart() {
        val start = score("week", "Weekend Slowed", "Jordan Burns")
        val inside = score("week", "Biweekly", "Jordan Burns")
        assertTrue(inside > 0)
        assertTrue(start > inside)
    }

    @Test fun titleBeatsArtistBeatsAlbum() {
        val title = score("weeknd", "Weeknd", "X", "Y")
        val artist = score("weeknd", "Z", "The Weeknd", "Y")
        val album = score("weeknd", "Z", "X", "The Weeknd Collection")
        assertTrue(title > artist, "title $title artist $artist")
        assertTrue(artist > album, "artist $artist album $album")
    }

    @Test fun wholeTitleComesFirst() {
        val exact = score("halo", "Halo", "Beyonce")
        val longer = score("halo", "Halo Reach Theme", "Someone")
        assertTrue(exact > longer)
    }

    @Test fun playsBreakTies() {
        assertTrue(score("halo", "Halo", plays = 40) > score("halo", "Halo", plays = 0))
    }

    @Test fun indianScriptsKeepTheirMarks() {
        assertTrue(score("मौसम", "मौसम", "कोई") > 0)
        assertEquals(0, score("गाना", "मौसम", "कोई"))
    }

    @Test fun foldKeepsLettersOfOtherScripts() {
        assertEquals("cafe", foldSearchText("Café"))
        assertEquals("मौसम", foldSearchText("मौसम"))
        assertEquals(listOf("hymn", "for", "the", "weekend"), searchWordsOf("Hymn for the Weekend!"))
    }

    @Test fun slipsCountSwappedNeighboursAsOne() {
        assertEquals(1, slips("weekend", "weekned", 2))
        assertEquals(3, slips("abcdef", "uvwxyz", 2))
    }
}
