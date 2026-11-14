package io.max.paperplay.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageTest {
    @Test fun scriptsPointToTheirLanguage() {
        assertEquals(listOf("Japanese"), languagesOfText("フェスティバルの夜へ", "ブルック"))
        assertEquals(listOf("Korean"), languagesOfText("봄날", "BTS"))
        assertEquals(listOf("Hindi"), languagesOfText("तौबा तौबा"))
        assertEquals(listOf("Malayalam"), languagesOfText("ഓർമ്മകൾ"))
        assertEquals(listOf("Russian"), languagesOfText("Мой мармеладный", "Katya Lel"))
        // Latin letters say nothing; characters shared by Chinese and Japanese could be either.
        assertTrue(languagesOfText("Tauba Tauba", "Karan Aujla").isEmpty())
        assertEquals(listOf("Chinese", "Japanese"), languagesOfText("夜空"))
    }

    @Test fun topicsNamedForALanguage() {
        assertEquals("Japanese", topicLanguage("Japanese"))
        assertEquals("Hindi", topicLanguage("Bollywood"))
        assertEquals("Malayalam", topicLanguage("Mollywood"))
        assertEquals("Telugu", topicLanguage("Tollywood"))
        assertEquals("English", topicLanguage("Hollywood Favourites"))
        assertNull(topicLanguage("Sad Songs"))
    }

    @Test fun aSearchWordMatchesTheStartOfALanguage() {
        assertTrue(languageMatches("jap", listOf("Japanese")))
        assertTrue(!languageMatches("ja", listOf("Japanese")))
        assertTrue(!languageMatches("hindi", listOf("Tamil")))
    }
}
