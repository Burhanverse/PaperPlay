package io.max.paperplay.core.recs

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.player.AudioAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How each song sounds, measured from its file by the Rust engine (layout in `engine/src/features.rs`), kept in its own table so
 * the Flutter-shared schema stays as it is. A song is measured when its download completes, and cached songs are worked through
 * in the background, one at a time.
 */
class AudioFeatureStore(
    private val driver: SqlDriver,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** False while heavy background work should wait (hidden on battery). */
    private val canRun: () -> Boolean = { true },
    /** Local path of a cached song's complete file, or null when it is not on this device. */
    private val pathOf: suspend (uniqueId: String) -> String? = { null },
) {
    private val version = AudioAnalysis.version
    private val lock = Mutex()
    private var backfill: Job? = null

    init {
        driver.execute(
            null,
            "CREATE TABLE IF NOT EXISTS audio_features (unique_id TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, v TEXT, measured_at INTEGER NOT NULL)",
            0,
        )
    }

    /** False on platforms that cannot measure (the desktop for now). */
    val supported: Boolean get() = version > 0

    fun count(): Long = driver.executeQuery(null, "SELECT COUNT(*) FROM audio_features WHERE v IS NOT NULL", { c ->
        QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L)
    }, 0).value

    /** Every measured vector by song id. */
    fun all(): Map<String, FloatArray> = driver.executeQuery(null, "SELECT unique_id, v FROM audio_features WHERE v IS NOT NULL", { c ->
        val out = HashMap<String, FloatArray>()
        while (c.next().value) {
            val id = c.getString(0) ?: continue
            decode(c.getString(1))?.let { out[id] = it }
        }
        QueryResult.Value(out)
    }, 0).value

    private fun measured(uid: String): Boolean = driver.executeQuery(null, "SELECT 1 FROM audio_features WHERE unique_id = ? AND version = ?", { c ->
        QueryResult.Value(c.next().value)
    }, 2) { bindString(0, uid); bindLong(1, version.toLong()) }.value

    /** Measures [uid] from the complete file at [path] unless it is already measured. A file that cannot be measured is stamped too. */
    fun onDownloaded(uid: String, path: String) {
        if (!supported) return
        scope.launch(Dispatchers.Default) { measure(uid, path) }
    }

    private suspend fun measure(uid: String, path: String) = lock.withLock {
        if (measured(uid)) return@withLock
        val v = runCatching { AudioAnalysis.analyze(path) }.getOrNull()
        io.max.paperplay.player.PlayLog.log("recs", if (v == null) "measure $uid: no result" else "measure $uid: ${v[14].toInt()} bpm, key ${v[17].toInt()} ${if (v[18] > 0.5f) "major" else "minor"}, ${v[0].toInt()} dB")
        driver.execute(null, "INSERT OR REPLACE INTO audio_features (unique_id, version, v, measured_at) VALUES (?, ?, ?, ?)", 4) {
            bindString(0, uid); bindLong(1, version.toLong()); bindString(2, v?.let(::encode)); bindLong(3, clock())
        }
    }

    /** Works through cached songs not measured yet, pausing while background work should wait. Safe to call again. */
    fun startBackfill(cachedIds: () -> List<String>) {
        if (!supported || backfill?.isActive == true) return
        backfill = scope.launch(Dispatchers.Default) {
            for (uid in cachedIds()) {
                if (!isActive) break
                while (!canRun()) delay(60_000)
                if (measured(uid)) continue
                val path = runCatching { pathOf(uid) }.getOrNull() ?: continue
                measure(uid, path)
                delay(500)
            }
        }
    }

    companion object {
        fun encode(v: FloatArray): String = v.joinToString(",")
        fun decode(s: String?): FloatArray? = s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()?.takeIf { it.isNotEmpty() }
    }
}
