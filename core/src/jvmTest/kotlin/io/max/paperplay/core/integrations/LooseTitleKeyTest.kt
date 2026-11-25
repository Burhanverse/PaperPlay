package io.max.paperplay.core.integrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LooseTitleKeyTest {
    @Test
    fun radioEditAfterADashMatchesTheBracketForms() {
        val spotify = looseTitleKey("Dusk Till Dawn (feat. Sia) - Radio Edit", "ZAYN, Sia")
        assertEquals(spotify, looseTitleKey("Dusk Till Dawn (radio edit)", "ZAYN feat. Sia"))
        assertEquals(spotify, looseTitleKey("Dusk Till Dawn (feat. Sia) [Radio Edit]", "ZAYN"))
    }

    @Test
    fun spotifyExtrasDoNotHideASong() {
        val artists = "Metro Boomin, Swae Lee, Lil Wayne, Offset"
        val film = "Annihilate (Spider-Man: Across the Spider-Verse)"
        assertEquals(looseTitleKey(film, "Metro Boomin"), looseTitleKey("$film (Metro Boomin & Swae Lee, Lil Wayne, Offset)", artists))
        assertEquals(looseTitleKey("Tum Todo Na (Male)", "Ash King"), looseTitleKey("Tum Todo Na (Male Version) [From \"I\"]", "Ash King & Sunidhi Chauhan"))
        assertEquals(looseTitleKey("Punkrocker", "Teddybears"), looseTitleKey("Punkrocker (feat. Iggy Pop) - As featured in \"Superman\"", "Teddybears, Iggy Pop"))
        assertEquals(looseTitleKey("Déjà Vu", "Beyoncé"), looseTitleKey("Deja Vu", "Beyonce"))
        assertEquals(looseTitleKey("Song - Acoustic Version", "Band"), looseTitleKey("Song (Acoustic)", "Band"))
        assertNotEquals(looseTitleKey("Tum Todo Na (Male)", "A"), looseTitleKey("Tum Todo Na (Female Version)", "A"))
        assertNotEquals(looseTitleKey("Song (Skrillex Remix)", "Skrillex"), looseTitleKey("Song", "Skrillex"))
    }

    @Test
    fun otherDashSuffixesStayDistinct() {
        assertNotEquals(looseTitleKey("Song - Live", "A"), looseTitleKey("Song", "A"))
    }
}
