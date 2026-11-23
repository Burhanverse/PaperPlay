package io.max.paperplay.player

/** Lightweight string preference abstraction for pure tests and platform storage bridges. */
interface PrefKeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/**
 * Unified Bit-perfect preference contract, non-destructive migration, and per-platform backup isolation.
 */
object BitPerfectPrefs {
    const val KEY_CANONICAL = "bit_perfect_mode"
    const val KEY_BACKUP_ANDROID = "bit_perfect_mode.android"
    const val KEY_BACKUP_DESKTOP = "bit_perfect_mode.desktop"
    const val KEY_ALLOW_EQ_IN_USB_EXCLUSIVE = "allow_eq_in_usb_exclusive"

    fun parseBool(raw: String?, default: Boolean): Boolean = when (raw?.trim()?.lowercase()) {
        "true", "on", "1" -> true
        "false", "off", "0" -> false
        else -> default
    }

    fun loadAllowEqInUsbExclusive(store: PrefKeyValueStore, fallback: PrefKeyValueStore? = null): Boolean {
        store.get(KEY_ALLOW_EQ_IN_USB_EXCLUSIVE)?.let { return parseBool(it, default = true) }
        fallback?.get(KEY_ALLOW_EQ_IN_USB_EXCLUSIVE)?.let {
            val v = parseBool(it, default = true)
            store.set(KEY_ALLOW_EQ_IN_USB_EXCLUSIVE, v.toString())
            return v
        }
        return true
    }

    fun saveAllowEqInUsbExclusive(store: PrefKeyValueStore, mirror: PrefKeyValueStore?, allow: Boolean) {
        val encoded = allow.toString()
        store.set(KEY_ALLOW_EQ_IN_USB_EXCLUSIVE, encoded)
        mirror?.set(KEY_ALLOW_EQ_IN_USB_EXCLUSIVE, encoded)
    }

    // Legacy keys kept read-only after one-time non-destructive migration.
    const val LEGACY_PC_BITPERFECT = "pc_bitperfect"
    const val LEGACY_PC_EXCLUSIVE = "pc_exclusive"
    const val LEGACY_USB_EXCLUSIVE = "usb_exclusive_mode"

    fun platformBackupKey(isPhone: Boolean): String = if (isPhone) KEY_BACKUP_ANDROID else KEY_BACKUP_DESKTOP

    fun parseMode(raw: String?): BitPerfectMode? = when (raw?.trim()?.lowercase()) {
        "true", "on", "1" -> BitPerfectMode.ON
        "false", "off", "0" -> BitPerfectMode.OFF
        else -> null
    }

    fun encodeMode(mode: BitPerfectMode): String = if (mode == BitPerfectMode.ON) "true" else "false"

    /** Non-destructive desktop load + one-time migration from legacy keys (`pc_bitperfect`, `pc_exclusive`). */
    fun loadDesktop(store: PrefKeyValueStore): BitPerfectMode {
        parseMode(store.get(KEY_CANONICAL))?.let { mode ->
            if (store.get(KEY_BACKUP_DESKTOP) == null) {
                store.set(KEY_BACKUP_DESKTOP, encodeMode(mode))
            }
            return mode
        }
        parseMode(store.get(KEY_BACKUP_DESKTOP))?.let { mode ->
            store.set(KEY_CANONICAL, encodeMode(mode))
            return mode
        }
        val legacyPc = parseMode(store.get(LEGACY_PC_BITPERFECT)) == BitPerfectMode.ON ||
            parseMode(store.get(LEGACY_PC_EXCLUSIVE)) == BitPerfectMode.ON
        val migrated = if (legacyPc) BitPerfectMode.ON else BitPerfectMode.OFF
        if (store.get(LEGACY_PC_BITPERFECT) != null || store.get(LEGACY_PC_EXCLUSIVE) != null) {
            val encoded = encodeMode(migrated)
            store.set(KEY_CANONICAL, encoded)
            store.set(KEY_BACKUP_DESKTOP, encoded)
        }
        return migrated
    }

    fun saveDesktop(store: PrefKeyValueStore, mode: BitPerfectMode) {
        val encoded = encodeMode(mode)
        store.set(KEY_CANONICAL, encoded)
        store.set(KEY_BACKUP_DESKTOP, encoded)
    }

    /**
     * Non-destructive Android load: SharedPreferences ([sp]) is the authoritative runtime store
     * (readable synchronously inside PlaybackService before SQLite/UI). Default on fresh install is [BitPerfectMode.OFF].
     */
    fun loadAndroid(sp: PrefKeyValueStore, sqliteFallback: PrefKeyValueStore? = null): BitPerfectMode {
        parseMode(sp.get(KEY_CANONICAL))?.let { return it }
        parseMode(sp.get(KEY_BACKUP_ANDROID))?.let { mode ->
            sp.set(KEY_CANONICAL, encodeMode(mode))
            return mode
        }
        parseMode(sp.get(LEGACY_USB_EXCLUSIVE))?.let { mode ->
            val encoded = encodeMode(mode)
            sp.set(KEY_CANONICAL, encoded)
            sp.set(KEY_BACKUP_ANDROID, encoded)
            return mode
        }
        sqliteFallback?.let { db ->
            val dbMode = parseMode(db.get(KEY_BACKUP_ANDROID))
                ?: parseMode(db.get(KEY_CANONICAL))
                ?: parseMode(db.get(LEGACY_USB_EXCLUSIVE))
            if (dbMode != null) {
                val encoded = encodeMode(dbMode)
                sp.set(KEY_CANONICAL, encoded)
                sp.set(KEY_BACKUP_ANDROID, encoded)
                return dbMode
            }
        }
        return BitPerfectMode.OFF
    }

    fun saveAndroid(sp: PrefKeyValueStore, sqliteMirror: PrefKeyValueStore?, mode: BitPerfectMode) {
        val encoded = encodeMode(mode)
        sp.set(KEY_CANONICAL, encoded)
        sp.set(KEY_BACKUP_ANDROID, encoded)
        sqliteMirror?.let {
            it.set(KEY_CANONICAL, encoded)
            it.set(KEY_BACKUP_ANDROID, encoded)
        }
    }

    /**
     * Same-platform restore carries only that platform's `bit_perfect_mode.<platform>` key.
     * Cross-platform restore returns null so desktop backups never enable Android USB exclusive (and vice versa).
     */
    fun resolveBackupRestore(isPhone: Boolean, restoredPrefs: Map<String, String>): BitPerfectMode? {
        val key = platformBackupKey(isPhone)
        return parseMode(restoredPrefs[key])
    }
}

/** Monotonic elapsed time in milliseconds for rate/underrun timing windows. */
internal expect fun monotonicNowMs(): Long

/** Platform-backed bit-perfect preference bridge. */
expect object PlatformBitPerfectPrefs {
    fun load(sqliteGet: ((String) -> String?)? = null, sqliteSet: ((String, String) -> Unit)? = null): BitPerfectMode
    fun save(mode: BitPerfectMode, sqliteSet: ((String, String) -> Unit)? = null)
    fun loadAllowEqInUsbExclusive(sqliteGet: ((String) -> String?)? = null, sqliteSet: ((String, String) -> Unit)? = null): Boolean
    fun saveAllowEqInUsbExclusive(allow: Boolean, sqliteSet: ((String, String) -> Unit)? = null)
    fun syncFromRestoredPrefs(restoredPrefs: Map<String, String>)
}
