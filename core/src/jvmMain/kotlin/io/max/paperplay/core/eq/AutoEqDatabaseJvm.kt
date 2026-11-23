package io.max.paperplay.core.eq

import io.max.paperplay.core.app.appLog
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private class JvmAutoEqCatalog(private val conn: Connection) : AutoEqCatalog {
    override val isAvailable: Boolean = true

    override val entryCount: Int by lazy {
        synchronized(conn) {
            runCatching {
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM autoeq_entries").use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            }.getOrDefault(0)
        }
    }

    override fun sources(): List<String> = synchronized(conn) {
        runCatching {
            val out = ArrayList<String>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT DISTINCT source FROM autoeq_entries ORDER BY source COLLATE NOCASE ASC").use { rs ->
                    while (rs.next()) out.add(rs.getString(1))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    override fun forms(): List<String> = synchronized(conn) {
        runCatching {
            val out = ArrayList<String>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT DISTINCT form FROM autoeq_entries ORDER BY form COLLATE NOCASE ASC").use { rs ->
                    while (rs.next()) out.add(rs.getString(1))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    override fun search(
        query: String,
        sourceFilter: String?,
        formFilter: String?,
        limit: Int,
    ): List<AutoEqEntry> = synchronized(conn) {
        val lim = limit.coerceIn(1, 200)
        val fts = buildFts5Query(query)
        val src = sourceFilter?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
        val frm = formFilter?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }

        val sql = StringBuilder()
        val params = ArrayList<Any>()

        if (fts.isNotEmpty()) {
            sql.append(
                "SELECT e.id, e.model, e.source, e.form, e.preamp_db, e.bands_blob, e.raw_path " +
                    "FROM autoeq_fts f JOIN autoeq_entries e ON e.id = f.rowid " +
                    "WHERE autoeq_fts MATCH ?",
            )
            params.add(fts)
            if (src != null) { sql.append(" AND e.source = ?"); params.add(src) }
            if (frm != null) { sql.append(" AND e.form = ?"); params.add(frm) }
            sql.append(" ORDER BY bm25(autoeq_fts), length(e.model) ASC LIMIT ?")
            params.add(lim)
        } else {
            sql.append("SELECT id, model, source, form, preamp_db, bands_blob, raw_path FROM autoeq_entries WHERE 1=1")
            if (src != null) { sql.append(" AND source = ?"); params.add(src) }
            if (frm != null) { sql.append(" AND form = ?"); params.add(frm) }
            sql.append(" ORDER BY model COLLATE NOCASE ASC LIMIT ?")
            params.add(lim)
        }

        runCatching {
            val out = ArrayList<AutoEqEntry>()
            conn.prepareStatement(sql.toString()).use { ps ->
                params.forEachIndexed { idx, v ->
                    when (v) {
                        is Int -> ps.setInt(idx + 1, v)
                        else -> ps.setString(idx + 1, v.toString())
                    }
                }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(
                            AutoEqEntry(
                                id = rs.getLong(1),
                                model = rs.getString(2),
                                source = rs.getString(3),
                                form = rs.getString(4),
                                preampDb = rs.getFloat(5),
                                bandsBlob = rs.getString(6),
                                rawPath = rs.getString(7),
                            ),
                        )
                    }
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    override fun close() {
        runCatching { conn.close() }
    }
}

private object EmptyJvmAutoEqCatalog : AutoEqCatalog {
    override val isAvailable: Boolean = false
    override val entryCount: Int = 0
    override fun sources(): List<String> = emptyList()
    override fun forms(): List<String> = emptyList()
    override fun search(query: String, sourceFilter: String?, formFilter: String?, limit: Int): List<AutoEqEntry> = emptyList()
}

actual fun openAutoEqCatalog(cacheDir: String?): AutoEqCatalog = runCatching {
    val dbFile = locateOrExtractDb(cacheDir) ?: return EmptyJvmAutoEqCatalog
    val conn = DriverManager.getConnection("jdbc:sqlite:file:${dbFile.absolutePath}?mode=ro")
    JvmAutoEqCatalog(conn)
}.onFailure {
    appLog("autoeq", "Failed to open JVM autoeq.db: $it")
}.getOrDefault(EmptyJvmAutoEqCatalog)

private fun locateOrExtractDb(cacheDir: String?): File? {
    val stream = AutoEqCatalog::class.java.getResourceAsStream("/autoeq.db")
    if (stream != null) {
        val dir = cacheDir?.let { File(it) } ?: File(System.getProperty("java.io.tmpdir"), "paperplay-autoeq")
        dir.mkdirs()
        val dest = File(dir, "autoeq.db")
        val bytes = stream.use { it.readBytes() }
        if (!dest.exists() || dest.length() != bytes.size.toLong()) {
            val tmp = File(dir, "autoeq.db.tmp")
            tmp.writeBytes(bytes)
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        }
        return dest
    }
    val cwd = File(System.getProperty("user.dir") ?: ".")
    val fallbackCandidates = listOf(
        File(cwd, "androidApp/src/main/assets/autoeq.db"),
        File(cwd.parentFile ?: cwd, "androidApp/src/main/assets/autoeq.db"),
        File(cwd, "core/src/jvmMain/resources/autoeq.db"),
        File(cwd.parentFile ?: cwd, "core/src/jvmMain/resources/autoeq.db"),
    )
    return fallbackCandidates.firstOrNull { it.isFile && it.length() > 0L }
}
