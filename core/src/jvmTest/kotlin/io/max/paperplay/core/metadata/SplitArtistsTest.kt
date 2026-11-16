package io.max.paperplay.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitArtistsTest {
    @Test fun aCreditSplitsIntoEachArtist() {
        assertEquals(listOf("Lyrical Lemonade", "Juice WRLD", "Cordae"), splitArtists("Lyrical Lemonade, Juice WRLD, Cordae"))
        assertEquals(listOf("A", "B", "C"), splitArtists("A & B feat. C"))
        assertEquals(listOf("Muse"), splitArtists("Muse"))
        assertEquals(emptyList(), splitArtists(" , "))
    }
}
