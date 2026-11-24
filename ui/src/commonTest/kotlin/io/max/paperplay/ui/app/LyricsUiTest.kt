package io.max.paperplay.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsUiTest {
    @Test
    fun resolveSecondaryStateHidesWhenUnavailableOrIdentical() {
        val none = resolveSecondaryState(null, null, enabled = true, preferredKind = "romanized", primaryText = "abc")
        assertFalse(none.available)
        assertNull(none.activeText)

        val same = resolveSecondaryState("[00:10.00]hello", null, enabled = true, preferredKind = "romanized", primaryText = "[00:10.00]hello")
        assertFalse(same.available)

        val romaOnly = resolveSecondaryState("[00:10.00]ohayou", null, enabled = true, preferredKind = "translation", primaryText = "[00:10.00]おはよう")
        assertTrue(romaOnly.available)
        assertEquals("Romanized", romaOnly.chipLabel)
        assertEquals("[00:10.00]ohayou", romaOnly.activeText)
    }

    @Test
    fun nextSecondaryPrefCyclesCleanly() {
        // Both tracks: romanized -> translation -> off -> romanized
        assertEquals(true to "translation", nextSecondaryPref("r", "t", enabled = true, preferredKind = "romanized"))
        assertEquals(false to "romanized", nextSecondaryPref("r", "t", enabled = true, preferredKind = "translation"))
        assertEquals(true to "romanized", nextSecondaryPref("r", "t", enabled = false, preferredKind = "romanized"))

        // Single track toggles on <-> off
        assertEquals(false to "romanized", nextSecondaryPref("r", null, enabled = true, preferredKind = "romanized"))
        assertEquals(true to "romanized", nextSecondaryPref("r", null, enabled = false, preferredKind = "romanized"))
    }

    @Test
    fun lyricsLineAlphaMaintainsHierarchyInLightAndDark() {
        for (dark in listOf(true, false)) {
            val active = lyricsLineAlpha(index = 3, current = 3, dark = dark)
            val upcoming = lyricsLineAlpha(index = 4, current = 3, dark = dark)
            val sung = lyricsLineAlpha(index = 2, current = 3, dark = dark)
            assertTrue(active > upcoming)
            assertTrue(upcoming > sung)
            assertTrue(sung >= 0.35f)
        }
    }

    @Test
    fun lyricsTextSizeScalesAreOrdered() {
        assertTrue(LyricsTextSize.Small.scale < LyricsTextSize.Medium.scale)
        assertTrue(LyricsTextSize.Medium.scale < LyricsTextSize.Large.scale)
    }
}
