package io.max.paperplay.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {
    @Test
    fun linksAndInternalSectionsAreLeftOut() {
        val md = """
            ### Improved
            - Storage page shows what is on the device ([`de2e981`](https://github.com/x/y/commit/de2e981abc))

            ### Fixed
            - Seek bar no longer jumps ([`1234567`](https://github.com/x/y/commit/1234567))

            ### Documentation
            - Desktop test still to do ([`0abea9a`](https://github.com/x/y/commit/0abea9a))

            ### Maintenance
            - Tag check ([`9944942`](https://github.com/x/y/commit/9944942))

            **Full changes:** [v1.11.0...v1.12.0](https://github.com/x/y/compare/v1.11.0...v1.12.0)
        """.trimIndent()
        assertEquals(
            listOf(
                true to "Improved", false to "Storage page shows what is on the device",
                true to "Fixed", false to "Seek bar no longer jumps",
            ),
            userNotes(md),
        )
    }
}
