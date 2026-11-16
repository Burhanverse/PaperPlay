package io.max.paperplay.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilationTest {
    @Test fun compilationNamesAreRecognised() {
        for (n in listOf("BRAVO Hits 95", "Top 100 Hits", "Now That's What I Call Music! 45", "Various Artists", "Summer Hits 2016", "Kuschelrock 30")) assertTrue(isCompilationAlbum(n), n)
        for (n in listOf("Collage", "Anbarey", "Memories...Do Not Open", "Hits and Misses", "")) assertFalse(isCompilationAlbum(n), n)
    }

    private fun m(album: String) = ExternalMatch("Deezer", "Closer", "The Chainsmokers", album, 244, "https://x/$album")

    @Test fun theSongsOwnAlbumBeatsACompilation() {
        val results = listOf(m("BRAVO Hits 95"), m("Collage"), m("Top 100"))
        assertEquals("Collage", pickExternal(results, "Closer", "The Chainsmokers", 244)?.album)
        assertEquals("Collage", pickExternal(results, "Closer", "The Chainsmokers", 244, "Collage")?.album)
    }

    @Test fun aCoverNeverComesFromACompilation() {
        val results = listOf(m("BRAVO Hits 95"), m("Top 100"))
        assertNull(pickExternal(results, "Closer", "The Chainsmokers", 244, allowCompilation = false))
        assertEquals("BRAVO Hits 95", pickExternal(results, "Closer", "The Chainsmokers", 244)?.album)
    }
}
