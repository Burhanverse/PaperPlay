package io.max.paperplay.ui.app

import io.max.paperplay.core.update.ChangelogParser
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {
    @Test
    fun commitLinksAreSplitFromTheText() {
        val md = """
            ### Improved
            - Storage page shows what is on the device ([`de2e981`](https://github.com/x/y/commit/de2e981abc))

            ### Fixed
            - Seek bar no longer jumps ([`1234567`](https://github.com/x/y/commit/1234567))

            **Full changes:** [v1.11.0...v1.12.0](https://github.com/x/y/compare/v1.11.0...v1.12.0)
        """.trimIndent()
        val doc = ChangelogParser.parseReleaseBody(md)
        assertEquals(listOf("Improved", "Fixed"), doc.groups.map { it.heading })
        assertEquals(
            listOf("Storage page shows what is on the device", "Seek bar no longer jumps"),
            doc.groups.flatMap { g -> g.items.map { it.text } },
        )
        assertEquals(listOf("de2e981", "1234567"), doc.groups.flatMap { g -> g.items.map { it.commitSha } })
        assertEquals("v1.11.0...v1.12.0", doc.compareLabel)
        assertEquals("https://github.com/x/y/compare/v1.11.0...v1.12.0", doc.compareUrl)
    }
}
