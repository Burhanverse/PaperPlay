package io.max.paperplay.player

internal actual fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

actual object PlatformBitPerfectPrefs {
    actual fun load(sqliteGet: ((String) -> String?)?, sqliteSet: ((String, String) -> Unit)?): BitPerfectMode {
        val get = sqliteGet ?: return BitPerfectMode.OFF
        val store = object : PrefKeyValueStore {
            override fun get(key: String): String? = get(key)
            override fun set(key: String, value: String) { sqliteSet?.invoke(key, value) }
        }
        return BitPerfectPrefs.loadDesktop(store)
    }

    actual fun save(mode: BitPerfectMode, sqliteSet: ((String, String) -> Unit)?) {
        val set = sqliteSet ?: return
        val store = object : PrefKeyValueStore {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String) { set(key, value) }
        }
        BitPerfectPrefs.saveDesktop(store, mode)
    }

    actual fun syncFromRestoredPrefs(restoredPrefs: Map<String, String>) {
        // Desktop reads SQLite directly; resolveBackupRestore handles isolation on read.
    }
}
