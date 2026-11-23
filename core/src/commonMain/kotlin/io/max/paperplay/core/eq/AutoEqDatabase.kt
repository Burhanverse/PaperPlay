package io.max.paperplay.core.eq

import io.max.paperplay.player.ParametricBand
import io.max.paperplay.player.ParametricEqProfile

/** One headphone/IEM correction entry in the offline AutoEQ database (jaakkopasanen/AutoEq, MIT). */
data class AutoEqEntry(
    val id: Long,
    val model: String,
    val source: String,
    val form: String,
    val preampDb: Float,
    val bandsBlob: String,
    val rawPath: String = "",
) {
    fun toProfile(): ParametricEqProfile {
        val bands: List<ParametricBand> = ParametricEqProfile.fromBandsBlob(bandsBlob)
        return ParametricEqProfile(
            id = "autoeq-$id",
            name = "$model ($source)",
            source = "AutoEQ / $source",
            preampDb = preampDb,
            autoPreamp = false,
            limiterEnabled = true,
            bands = bands,
        )
    }
}

/** Read-only offline FTS5 AutoEQ catalog interface. */
interface AutoEqCatalog {
    val isAvailable: Boolean
    val entryCount: Int
    fun sources(): List<String>
    fun forms(): List<String>
    fun search(
        query: String,
        sourceFilter: String? = null,
        formFilter: String? = null,
        limit: Int = 60,
    ): List<AutoEqEntry>
    fun close() {}
}

/** Builds a safe FTS5 prefix MATCH expression from free-text user input (e.g. `"HD 650"` -> `"HD"* "650"*`). */
internal fun buildFts5Query(raw: String): String {
    val tokens = raw.trim()
        .split(Regex("""\s+"""))
        .map { it.replace(Regex("""["'*^:()+-]"""), "").trim() }
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return ""
    return tokens.joinToString(" ") { "\"$it\"*" }
}

expect fun openAutoEqCatalog(cacheDir: String? = null): AutoEqCatalog
