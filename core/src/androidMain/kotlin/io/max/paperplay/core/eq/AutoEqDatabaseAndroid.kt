package io.max.paperplay.core.eq

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.max.paperplay.core.app.appLog
import java.io.File

object AutoEqAndroidContext {
    @Volatile
    internal var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

private class AndroidAutoEqCatalog(private val db: SQLiteDatabase) : AutoEqCatalog {
    private val ftsWorks: Boolean = runCatching {
        db.rawQuery("SELECT rowid FROM autoeq_fts WHERE autoeq_fts MATCH '\"test\"*' LIMIT 1", null).use { }
        true
    }.getOrDefault(false)

    override val isAvailable: Boolean = true

    override val entryCount: Int by lazy {
        runCatching {
            db.rawQuery("SELECT COUNT(*) FROM autoeq_entries", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.getOrDefault(0)
    }

    override fun sources(): List<String> = runCatching {
        val out = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT source FROM autoeq_entries ORDER BY source COLLATE NOCASE ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        out
    }.getOrDefault(emptyList())

    override fun forms(): List<String> = runCatching {
        val out = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT form FROM autoeq_entries ORDER BY form COLLATE NOCASE ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        out
    }.getOrDefault(emptyList())

    override fun search(
        query: String,
        sourceFilter: String?,
        formFilter: String?,
        limit: Int,
    ): List<AutoEqEntry> {
        val lim = limit.coerceIn(1, 200)
        val fts = buildFts5Query(query)
        val src = sourceFilter?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
        val frm = formFilter?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }

        val sql = StringBuilder()
        val args = ArrayList<String>()

        if (fts.isNotEmpty() && ftsWorks) {
            sql.append(
                "SELECT e.id, e.model, e.source, e.form, e.preamp_db, e.bands_blob, e.raw_path " +
                    "FROM autoeq_fts f JOIN autoeq_entries e ON e.id = f.rowid " +
                    "WHERE autoeq_fts MATCH ?",
            )
            args.add(fts)
            if (src != null) { sql.append(" AND e.source = ?"); args.add(src) }
            if (frm != null) { sql.append(" AND e.form = ?"); args.add(frm) }
            sql.append(" ORDER BY bm25(autoeq_fts), length(e.model) ASC LIMIT ?")
            args.add(lim.toString())
        } else {
            sql.append("SELECT id, model, source, form, preamp_db, bands_blob, raw_path FROM autoeq_entries WHERE 1=1")
            if (query.isNotBlank()) {
                for (tok in query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    sql.append(" AND (model LIKE ? OR source LIKE ? OR form LIKE ?)")
                    val pat = "%$tok%"
                    args.add(pat); args.add(pat); args.add(pat)
                }
            }
            if (src != null) { sql.append(" AND source = ?"); args.add(src) }
            if (frm != null) { sql.append(" AND form = ?"); args.add(frm) }
            sql.append(" ORDER BY model COLLATE NOCASE ASC LIMIT ?")
            args.add(lim.toString())
        }

        return runCatching {
            val out = ArrayList<AutoEqEntry>()
            db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        AutoEqEntry(
                            id = c.getLong(0),
                            model = c.getString(1),
                            source = c.getString(2),
                            form = c.getString(3),
                            preampDb = c.getFloat(4),
                            bandsBlob = c.getString(5),
                            rawPath = c.getString(6),
                        ),
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    override fun close() {
        runCatching { db.close() }
    }
}

private object EmptyAutoEqCatalog : AutoEqCatalog {
    override val isAvailable: Boolean = false
    override val entryCount: Int = 0
    override fun sources(): List<String> = emptyList()
    override fun forms(): List<String> = emptyList()
    override fun search(query: String, sourceFilter: String?, formFilter: String?, limit: Int): List<AutoEqEntry> = emptyList()
}

actual fun openAutoEqCatalog(cacheDir: String?): AutoEqCatalog {
    val ctx = AutoEqAndroidContext.appContext ?: return EmptyAutoEqCatalog
    return runCatching {
        val targetDir = cacheDir?.let { File(it) } ?: ctx.filesDir
        targetDir.mkdirs()
        val dbFile = File(targetDir, "autoeq.db")
        val assetLen = runCatching { ctx.assets.openFd("autoeq.db").use { it.length } }.getOrDefault(-1L)
        val needsCopy = !dbFile.exists() || dbFile.length() == 0L || (assetLen > 0L && dbFile.length() != assetLen)
        if (needsCopy) {
            val tmp = File(targetDir, "autoeq.db.tmp")
            ctx.assets.open("autoeq.db").use { input ->
                tmp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (dbFile.exists()) dbFile.delete()
            tmp.renameTo(dbFile)
        }
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        AndroidAutoEqCatalog(db)
    }.onFailure {
        appLog("autoeq", "Failed to open bundled autoeq.db: $it")
    }.getOrDefault(EmptyAutoEqCatalog)
}
