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
    fun otherDashSuffixesStayDistinct() {
        assertNotEquals(looseTitleKey("Song - Live", "A"), looseTitleKey("Song", "A"))
    }
}
