package io.max.paperplay.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guardrail test: prevents raw `tween(`, `spring(`, or `rememberInfiniteTransition(` calls
 * from creeping back into `ui/src/commonMain` outside `Motion.kt` and `AmbientClock.kt`.
 */
class MotionLintTest {
    private val forbidden = listOf(
        Regex("""\btween\s*[<(]"""),
        Regex("""\bspring\s*[<(]"""),
        Regex("""\brememberInfiniteTransition\s*\("""),
    )

    @Test
    fun noRawAnimationSpecsOrInfiniteTransitionsOutsideMotionFiles() {
        val candidates = listOf(
            File("src/commonMain/kotlin"),
            File("ui/src/commonMain/kotlin"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate ui/src/commonMain/kotlin from ${File(".").absolutePath}")

        val allowedFiles = setOf("Motion.kt", "AmbientClock.kt")
        val violations = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowedFiles }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    val prev = if (idx > 0) lines[idx - 1] else ""
                    if (trimmed.contains("@MotionRawAllowed") || prev.contains("@MotionRawAllowed")) return@forEachIndexed
                    for (pat in forbidden) {
                        if (pat.containsMatchIn(line)) {
                            violations += "${file.relativeTo(root).path}:${idx + 1}: $trimmed"
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Found raw animation specs outside Motion.kt/AmbientClock.kt:\n${violations.joinToString("\n")}",
        )
    }
}
