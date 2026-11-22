package io.max.paperplay.core.update

/** One item in a release's change list, with optional commit SHA and link peeled off the end. */
data class ChangelogBullet(
    val text: String,
    val commitSha: String? = null,
    val commitUrl: String? = null,
)

/** One grouped section inside a release (for example, "Added", "Fixed", "Improved", "Other changes"). */
data class ChangelogGroup(
    val heading: String,
    val items: List<ChangelogBullet>,
)

/** Parsed body of a single release (from GitHub release `body` or one `## [version]` section of `CHANGELOG.md`). */
data class ReleaseNotesDoc(
    val groups: List<ChangelogGroup>,
    val compareLabel: String? = null,
    val compareUrl: String? = null,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && compareLabel == null
}

/** One version section in the full change log history (`## [1.12.0] - 2026-11-22` or a GitHub release entry). */
data class ChangelogVersionEntry(
    val version: String,
    val date: String? = null,
    val prerelease: Boolean = false,
    val pageUrl: String? = null,
    val notes: ReleaseNotesDoc,
)

/**
 * Parses raw Markdown release notes from GitHub Releases and `CHANGELOG.md` into structured
 * sections, items, commit references, and compare links so the UI can format them cleanly.
 */
object ChangelogParser {
    private val versionHeadingRe = Regex("""^##\s+\[?([^\]\s]+)]?(?:\s*-\s*(\S+))?\s*$""")
    private val groupHeadingRe = Regex("""^###+\s+(.+?)\s*$""")
    private val bulletPrefixRe = Regex("""^[-*]\s+(.+?)\s*$""")
    // Matches trailing "([`de2e981`](https://.../commit/...))" or "([de2e981](https://...))"
    private val trailingCommitLinkRe = Regex("""^(.*?)\s*\(\[`?([0-9a-fA-F]{7,40})`?]\((https?://[^)\s]+)\)\)\s*$""")
    // Matches "**Full changes:** [v1.11.0...v1.12.0](https://.../compare/...)"
    private val fullChangesRe = Regex("""^\*\*Full changes:\*\*\s*\[([^\]]+)]\((https?://[^)\s]+)\)\s*$""")

    /** Parses the Markdown body of a single release. */
    fun parseReleaseBody(markdown: String): ReleaseNotesDoc {
        val groups = mutableListOf<ChangelogGroup>()
        var currentHeading: String? = null
        var currentItems = mutableListOf<ChangelogBullet>()
        var compareLabel: String? = null
        var compareUrl: String? = null

        fun flushGroup() {
            if (currentItems.isNotEmpty()) {
                groups += ChangelogGroup(currentHeading ?: "Changes", currentItems.toList())
                currentItems = mutableListOf()
            }
            currentHeading = null
        }

        for (rawLine in markdown.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val fullMatch = fullChangesRe.matchEntire(line)
            if (fullMatch != null) {
                compareLabel = fullMatch.groupValues[1].trim()
                compareUrl = fullMatch.groupValues[2].trim()
                continue
            }

            val groupMatch = groupHeadingRe.matchEntire(line)
            if (groupMatch != null) {
                flushGroup()
                currentHeading = cleanInlineMarkdown(groupMatch.groupValues[1])
                continue
            }

            if (line.startsWith("#")) {
                flushGroup()
                currentHeading = cleanInlineMarkdown(line.trimStart('#', ' '))
                continue
            }

            val bulletMatch = bulletPrefixRe.matchEntire(line)
            if (bulletMatch != null) {
                currentItems += parseBullet(bulletMatch.groupValues[1])
            } else {
                currentItems += parseBullet(line)
            }
        }
        flushGroup()
        return ReleaseNotesDoc(groups = groups, compareLabel = compareLabel, compareUrl = compareUrl)
    }

    /** Parses a full `CHANGELOG.md` document into newest-first version entries. */
    fun parseChangelogMarkdown(markdown: String): List<ChangelogVersionEntry> {
        val entries = mutableListOf<ChangelogVersionEntry>()
        var currentVersion: String? = null
        var currentDate: String? = null
        val currentBody = StringBuilder()

        fun flushVersion() {
            val ver = currentVersion ?: return
            val doc = parseReleaseBody(currentBody.toString())
            if (!doc.isEmpty) {
                val parsed = Version.parse(ver)
                entries += ChangelogVersionEntry(
                    version = ver.removePrefix("v"),
                    date = currentDate,
                    prerelease = parsed?.stage == Stage.Beta,
                    pageUrl = if (ver.equals("Unreleased", ignoreCase = true)) null
                    else "https://github.com/maxcodl/PaperPlay/releases/tag/v${ver.removePrefix("v")}",
                    notes = doc,
                )
            }
            currentVersion = null
            currentDate = null
            currentBody.setLength(0)
        }

        for (rawLine in markdown.lineSequence()) {
            val trimmed = rawLine.trim()
            val vMatch = versionHeadingRe.matchEntire(trimmed)
            if (vMatch != null) {
                flushVersion()
                currentVersion = vMatch.groupValues[1].trim()
                currentDate = vMatch.groupValues[2].trim().ifEmpty { null }
                continue
            }
            if (currentVersion != null) {
                currentBody.appendLine(rawLine)
            }
        }
        flushVersion()
        return entries
    }

    /** Converts a [ReleaseInfo] from the GitHub Releases API into a [ChangelogVersionEntry]. */
    fun fromReleaseInfo(release: ReleaseInfo): ChangelogVersionEntry = ChangelogVersionEntry(
        version = release.version.toString(),
        date = release.publishedAt?.take(10)?.takeIf { it.length == 10 },
        prerelease = release.prerelease,
        pageUrl = release.pageUrl.ifBlank { null },
        notes = parseReleaseBody(release.notes),
    )

    /**
     * Merges entries parsed from `CHANGELOG.md` with entries from GitHub Releases so both full history
     * and recent beta/pre-release tags appear without duplicates.
     */
    fun mergeEntries(
        changelogEntries: List<ChangelogVersionEntry>,
        releaseEntries: List<ChangelogVersionEntry>,
    ): List<ChangelogVersionEntry> {
        val byVersion = LinkedHashMap<String, ChangelogVersionEntry>()
        for (r in releaseEntries) {
            val key = r.version.removePrefix("v")
            byVersion[key] = r
        }
        for (c in changelogEntries) {
            val key = c.version.removePrefix("v")
            val existing = byVersion[key]
            if (existing == null) {
                byVersion[key] = c
            } else if (existing.date == null && c.date != null) {
                byVersion[key] = existing.copy(date = c.date)
            }
        }
        return byVersion.values.sortedWith { a, b ->
            val va = Version.parse(a.version)
            val vb = Version.parse(b.version)
            when {
                a.version.equals("Unreleased", ignoreCase = true) && !b.version.equals("Unreleased", ignoreCase = true) -> -1
                b.version.equals("Unreleased", ignoreCase = true) && !a.version.equals("Unreleased", ignoreCase = true) -> 1
                va != null && vb != null -> vb.compareTo(va)
                va != null -> -1
                vb != null -> 1
                else -> 0
            }
        }
    }

    internal fun parseBullet(raw: String): ChangelogBullet {
        val commitMatch = trailingCommitLinkRe.matchEntire(raw)
        return if (commitMatch != null) {
            ChangelogBullet(
                text = cleanInlineMarkdown(commitMatch.groupValues[1]),
                commitSha = commitMatch.groupValues[2].take(7),
                commitUrl = commitMatch.groupValues[3],
            )
        } else {
            ChangelogBullet(text = cleanInlineMarkdown(raw))
        }
    }

    /** Strips raw inline Markdown links `[label](url)` -> `label` and bold `**text**` -> `text`. */
    internal fun cleanInlineMarkdown(text: String): String {
        var s = text.trim()
        // Replace inline markdown links [label](url) with label
        s = s.replace(Regex("""\[`?([^]\n]+?)`?]\(https?://[^)\s]+\)"""), "$1")
        // Replace bold **text** with text
        s = s.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        return s.trim()
    }
}
