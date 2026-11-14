package io.max.paperplay.core.integrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackLinksTest {
    @Test fun songLinkAnswerGivesTheServicesItKnows() {
        val json = """{"linksByPlatform":{"spotify":{"url":"https://open.spotify.com/track/a"},"appleMusic":{"url":"https://music.apple.com/x"},"tidal":{"url":"https://tidal.com/t"},"napster":{"url":"https://napster.com/n"}}}"""
        assertEquals(
            mapOf("Spotify" to "https://open.spotify.com/track/a", "Apple Music" to "https://music.apple.com/x", "Tidal" to "https://tidal.com/t"),
            linksFromSongLink(json),
        )
    }

    @Test fun deezerLinkIsOnlyTheResultThatIsTheSong() {
        val json = """{"data":[
            {"title":"Blinding Lights (Remix)","duration":200,"link":"https://www.deezer.com/track/1","artist":{"name":"The Weeknd"}},
            {"title":"Blinding Lights","duration":262,"link":"https://www.deezer.com/track/2","artist":{"name":"Someone Else"}},
            {"title":"Blinding Lights","duration":203,"link":"https://www.deezer.com/track/3","artist":{"name":"The Weeknd"}}]}"""
        assertEquals("https://www.deezer.com/track/3", deezerLinkFrom(json, "Blinding Lights", "The Weeknd", 201))
        assertNull(deezerLinkFrom(json, "Blinding Lights", "The Weeknd", 400))
    }
}
