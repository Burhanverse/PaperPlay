package io.max.paperplay.ui.app

import io.max.paperplay.player.OutputInfo
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.SongUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModernPlayerTest {
    @Test
    fun playerStylePrefDefaultsToDefaultAndPersistsModern() {
        val store = mutableMapOf<String, String>()
        val prefs1 = UiPrefs({ store[it] }, { k, v -> store[k] = v })
        assertEquals(PlayerStyle.Default, prefs1.playerStyle)
        assertEquals(PlayerStyle.Default, resolveEffectivePlayerStyle(null, isAndroid = true))
        assertEquals(24f, prefs1.modernPlayerBlur)

        prefs1.putPlayerStyle(PlayerStyle.Modern)
        prefs1.putModernPlayerBlur(32f)
        assertEquals(PlayerStyle.Modern, prefs1.playerStyle)
        assertEquals("modern", store["player_style"])
        assertEquals(32f, prefs1.modernPlayerBlur)
        assertEquals("32.0", store["modern_player_blur"])

        val prefs2 = UiPrefs({ store[it] }, { k, v -> store[k] = v })
        assertEquals(PlayerStyle.Modern, prefs2.playerStyle)
        assertEquals(32f, prefs2.modernPlayerBlur)
        assertEquals(PlayerStyle.Modern, resolveEffectivePlayerStyle(prefs2, isAndroid = true))
        // Non-Android builds always fall back to Default
        assertEquals(PlayerStyle.Default, resolveEffectivePlayerStyle(prefs2, isAndroid = false))

        // Clamping & decode px mapping
        prefs2.putModernPlayerBlur(99f)
        assertEquals(40f, prefs2.modernPlayerBlur)
        prefs2.putModernPlayerBlur(-5f)
        assertEquals(0f, prefs2.modernPlayerBlur)
        assertEquals(256, modernBackdropDecodePx(0f))
        assertEquals(128, modernBackdropDecodePx(8f))
        assertEquals(64, modernBackdropDecodePx(16f))
        assertEquals(36, modernBackdropDecodePx(24f))
        assertEquals(24, modernBackdropDecodePx(40f))

        // Unknown value falls back to Default
        store["player_style"] = "unknown_style"
        val prefs3 = UiPrefs({ store[it] }, { k, v -> store[k] = v })
        assertEquals(PlayerStyle.Default, prefs3.playerStyle)
    }

    @Test
    fun qualityBadgesFollowRealFormatHiResRule() {
        val hiResFlac = SongUi("1", "Track", "Artist", qualityLabel = "FLAC 24/96", lossless = true)
        val b1 = resolveQualityBadges(hiResFlac, null)
        assertTrue(b1.isHiRes)
        assertEquals("FLAC", b1.codec)
        assertFalse(b1.isBitPerfect)

        val hiResRateOnly = SongUi("2", "Track", "Artist", qualityLabel = "FLAC 16/48", lossless = true)
        assertTrue(resolveQualityBadges(hiResRateOnly, null).isHiRes)

        val cdFlac = SongUi("3", "Track", "Artist", qualityLabel = "FLAC 16/44.1", lossless = true)
        val b3 = resolveQualityBadges(cdFlac, null)
        assertFalse(b3.isHiRes)
        assertEquals("FLAC", b3.codec)

        val lossyMp3 = SongUi("4", "Track", "Artist", qualityLabel = "MP3 320", lossless = false)
        val b4 = resolveQualityBadges(lossyMp3, null)
        assertFalse(b4.isHiRes)
        assertEquals("MP3", b4.codec)

        // Unknown format never claims Hi-Res or fabricates a codec
        val unknown = SongUi("5", "Track", "Artist", qualityLabel = null, lossless = false)
        val b5 = resolveQualityBadges(unknown, null)
        assertFalse(b5.isHiRes)
        assertNull(b5.codec)
        assertFalse(b5.isBitPerfect)

        // Live OutputInfo enriches depth/rate/codec and bit-perfect state
        val liveInfo = OutputInfo(codec = "ALAC", sampleRateHz = 96000, bitDepth = 24, bitPerfect = true)
        val b6 = resolveQualityBadges(SongUi("6", "Track", "Artist", qualityLabel = null, lossless = true), liveInfo)
        assertTrue(b6.isHiRes)
        assertEquals("ALAC", b6.codec)
        assertTrue(b6.isBitPerfect)
    }

    @Test
    fun playingFromResolvesAlbumArtistOrAll() {
        val s1 = SongUi("1", "A", "Muse", album = "Absolution")
        val s2 = SongUi("2", "B", "Muse", album = "Absolution")
        val s3 = SongUi("3", "C", "Muse", album = "Black Holes")
        val s4 = SongUi("4", "D", "Queen", album = "A Night at the Opera")

        assertEquals("Absolution", resolvePlayingFrom(PlaybackUiState(current = s1, queue = listOf(s1, s2))))
        assertEquals("Muse", resolvePlayingFrom(PlaybackUiState(current = s1, queue = listOf(s1, s3))))
        assertEquals("All", resolvePlayingFrom(PlaybackUiState(current = s1, queue = listOf(s1, s4))))
        assertEquals("All", resolvePlayingFrom(PlaybackUiState()))
    }
}
