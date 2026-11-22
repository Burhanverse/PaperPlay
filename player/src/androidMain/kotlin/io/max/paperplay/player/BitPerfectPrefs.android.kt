package io.max.paperplay.player

import android.content.Context
import android.os.SystemClock

internal actual fun monotonicNowMs(): Long = SystemClock.elapsedRealtime()

private const val SHARED_PREFS_NAME = "paperplay_prefs"

actual object PlatformBitPerfectPrefs {
    private fun spStore(ctx: Context): PrefKeyValueStore {
        val sp = ctx.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        return object : PrefKeyValueStore {
            override fun get(key: String): String? =
                if (sp.contains(key)) {
                    runCatching { sp.getString(key, null) }.getOrNull()
                        ?: runCatching { if (sp.getBoolean(key, false)) "true" else "false" }.getOrNull()
                } else null

            override fun set(key: String, value: String) {
                sp.edit().putString(key, value).apply()
            }
        }
    }

    actual fun load(sqliteGet: ((String) -> String?)?, sqliteSet: ((String, String) -> Unit)?): BitPerfectMode {
        val ctx = PlaybackCore.appContext
        val dbStore = sqliteGet?.let { g ->
            object : PrefKeyValueStore {
                override fun get(key: String): String? = g(key)
                override fun set(key: String, value: String) { sqliteSet?.invoke(key, value) }
            }
        }
        return if (ctx != null) {
            BitPerfectPrefs.loadAndroid(spStore(ctx), dbStore)
        } else if (dbStore != null) {
            BitPerfectPrefs.loadAndroid(dbStore, null)
        } else {
            BitPerfectMode.OFF
        }
    }

    actual fun save(mode: BitPerfectMode, sqliteSet: ((String, String) -> Unit)?) {
        val ctx = PlaybackCore.appContext
        val dbMirror = sqliteSet?.let { s ->
            object : PrefKeyValueStore {
                override fun get(key: String): String? = null
                override fun set(key: String, value: String) { s(key, value) }
            }
        }
        if (ctx != null) {
            BitPerfectPrefs.saveAndroid(spStore(ctx), dbMirror, mode)
        } else {
            val encoded = BitPerfectPrefs.encodeMode(mode)
            sqliteSet?.invoke(BitPerfectPrefs.KEY_CANONICAL, encoded)
            sqliteSet?.invoke(BitPerfectPrefs.KEY_BACKUP_ANDROID, encoded)
        }
    }

    actual fun syncFromRestoredPrefs(restoredPrefs: Map<String, String>) {
        val ctx = PlaybackCore.appContext ?: return
        val restored = BitPerfectPrefs.resolveBackupRestore(isPhone = true, restoredPrefs = restoredPrefs) ?: return
        val sp = spStore(ctx)
        val encoded = BitPerfectPrefs.encodeMode(restored)
        sp.set(BitPerfectPrefs.KEY_CANONICAL, encoded)
        sp.set(BitPerfectPrefs.KEY_BACKUP_ANDROID, encoded)
    }
}

/** Public Android entry point for backup restore to mirror restored preferences into SharedPreferences. */
fun syncRestoredPrefsFromAndroidContext(context: Context, restoredPrefs: Map<String, String>) {
    val appCtx = context.applicationContext
    if (PlaybackCore.appContext == null) {
        PlaybackCore.appContext = appCtx
    }
    PlatformBitPerfectPrefs.syncFromRestoredPrefs(restoredPrefs)
    val restoredMode = BitPerfectPrefs.resolveBackupRestore(isPhone = true, restoredPrefs = restoredPrefs)
    if (restoredMode != null) {
        PlaybackCore.savePersistedUsbExclusiveMode(appCtx, restoredMode == BitPerfectMode.ON)
    }
}
