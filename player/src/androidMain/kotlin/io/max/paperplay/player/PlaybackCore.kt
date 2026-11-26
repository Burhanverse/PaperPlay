package io.max.paperplay.player

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.SessionCommand
import java.util.concurrent.ConcurrentHashMap

/** Shared stream registry and player factory configuration between [AndroidPlayerEngine] and [PlaybackService]. */
internal object PlaybackCore {
    const val ACTION_SET_EQUALIZER = "io.max.paperplay.player.SET_EQUALIZER"
    const val EXTRA_EQ_GAINS = "gains"
    const val ACTION_SET_NORMALIZE = "io.max.paperplay.player.SET_NORMALIZE"
    const val EXTRA_NORMALIZE = "on"
    const val ACTION_SET_SINK_CHAIN = "io.max.paperplay.player.SET_SINK_CHAIN"
    const val EXTRA_SINK_CHAIN = "mode"
    const val ACTION_REBUILD_FOR_BIT_PERFECT = "io.max.paperplay.player.REBUILD_BIT_PERFECT"
    const val EXTRA_REBUILD_EXPLICIT = "explicit"
    val CMD_SET_NORMALIZE by lazy { SessionCommand(ACTION_SET_NORMALIZE, Bundle.EMPTY) }
    val CMD_SET_EQUALIZER by lazy { SessionCommand(ACTION_SET_EQUALIZER, Bundle.EMPTY) }
    val CMD_SET_SINK_CHAIN by lazy { SessionCommand(ACTION_SET_SINK_CHAIN, Bundle.EMPTY) }
    val CMD_REBUILD_FOR_BIT_PERFECT by lazy { SessionCommand(ACTION_REBUILD_FOR_BIT_PERFECT, Bundle.EMPTY) }

    @Volatile var appContext: Context? = null
    @Volatile var upstream: DataSource.Factory? = null
    @Volatile var eqGains: List<Float>? = null
    /** 31-band parametric EQ profile and master toggle; read lock-free on the audio sink thread. */
    @Volatile var parametricEq: ParametricEqProfile? = null
    @Volatile var parametricEnabled: Boolean = false
    /** When true, 31-band parametric EQ runs in-place on USB Exclusive / API 34 Direct output without falling back to AudioFlinger. */
    @Volatile var allowEqInUsbExclusive: Boolean = true
    /** True while the Rust EQ is actively processing PCM on the USB Exclusive / Direct path (for honest UI reporting). */
    @Volatile var usbEqActive: Boolean = false
    /** True while the Rust engine is in the audio path (set by the sink wrapper), so the output info tells the truth. */
    @Volatile var engineActive = false
    /** True while EngineAudioSink is actively wrapping the normal sink and feeding AudioTap after sink writes. */
    @Volatile var engineSinkHandlesTap = false
    /** Extra bass in dB for the Rust engine, 0 when the equalizer is off. */
    @Volatile var bassDb = 0f
    /** Engine level mode: true = exact curve (lowered by its peak), false = loud. */
    @Volatile var exactLevel = false
    /** A/B: true plays the untouched input at the processed level. */
    @Volatile var dryCompare = false
    /** Stereo width above the bass, 1 = as recorded. */
    @Volatile var width = 1f
    /** Earphone correction filters, 4 floats each (kind, Hz, Q, dB), or null. */
    @Volatile var correction: FloatArray? = null
    /** Engine option bits: 1 dynamic bass, 2 de-esser, 4 crossfeed. */
    @Volatile var options = 0
    /** What the Rust engine reads and writes, and its meters, for the output info. Set by the sink wrapper. */
    @Volatile var engineIn: String? = null
    @Volatile var engineOut: String? = null
    @Volatile var engineStats: FloatArray? = null
    @Volatile var normalize = false
    @Volatile var speed = 1f
    @Volatile var useUnifiedBitPerfect = USE_UNIFIED_BIT_PERFECT_DEFAULT
    @Volatile var devApi34MixerEnabled = false
    @Volatile var devUsbAdpfEnabled = false
    @Volatile var devUsbSilentKeepAliveEnabled = false
    @Volatile var usbExclusiveMode = false
    @Volatile var sinkChainMode: SinkChainMode = SinkChainMode.FULL
    @Volatile var usbActive = false
    @Volatile var usbStatus: String? = null
    @Volatile var devDirectTestActive = false
    @Volatile var onUsbReconfigurationRequested: (() -> Unit)? = null
    @Volatile var onRebuildPlayerForBitPerfect: ((Boolean) -> Unit)? = null
    @Volatile var activePlayer: ExoPlayer? = null
    @Volatile var playerPlaybackState: Int = androidx.media3.common.Player.STATE_IDLE
    @Volatile var playerPlayWhenReady: Boolean = false
    @Volatile var playerIsPlaying: Boolean = false
    @Volatile var playerHasError: Boolean = false
    val playIntentTracker = io.max.paperplay.player.usb.UserPlayIntentTracker()
    var userPlayIntent: Boolean
        get() = playIntentTracker.userWantsPlayback
        set(value) { playIntentTracker.reset(value) }
    val sources = ConcurrentHashMap<String, StreamSource>()

    private const val PREFS_NAME = "paperplay_prefs"
    private const val KEY_USB_EXCLUSIVE = "usb_exclusive_mode"
    private const val KEY_SINK_CHAIN = "sink_chain"
    private const val KEY_USE_UNIFIED_BIT_PERFECT = "dev_use_unified_bit_perfect"
    private const val KEY_DEV_USB_ADPF = "dev_usb_adpf"
    private const val KEY_DEV_USB_SILENT_KEEPALIVE = "dev_usb_silent_keepalive"

    fun savePersistedUsbExperiments(context: Context, adpf: Boolean? = null, silentKeepAlive: Boolean? = null) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ed = sp.edit()
        if (adpf != null) {
            devUsbAdpfEnabled = adpf
            ed.putBoolean(KEY_DEV_USB_ADPF, adpf)
        }
        if (silentKeepAlive != null) {
            devUsbSilentKeepAliveEnabled = silentKeepAlive
            ed.putBoolean(KEY_DEV_USB_SILENT_KEEPALIVE, silentKeepAlive)
        }
        ed.apply()
    }

    fun isNetworkMediaUri(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val lower = uri.lowercase()
        return lower.startsWith("stream://") || lower.startsWith("http://") || lower.startsWith("https://")
    }

    fun loadPersistedUseUnifiedBitPerfect(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (sp.contains(KEY_USE_UNIFIED_BIT_PERFECT)) {
            val v = sp.getBoolean(KEY_USE_UNIFIED_BIT_PERFECT, USE_UNIFIED_BIT_PERFECT_DEFAULT)
            useUnifiedBitPerfect = v
            return v
        }
        try {
            val dbFile = java.io.File(context.filesDir, "library.sqlite")
            if (dbFile.exists()) {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    db.rawQuery("SELECT value FROM prefs WHERE \"key\" = ?", arrayOf(KEY_USE_UNIFIED_BIT_PERFECT)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val v = cursor.getString(0) == "true"
                            sp.edit().putBoolean(KEY_USE_UNIFIED_BIT_PERFECT, v).apply()
                            useUnifiedBitPerfect = v
                            return v
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        useUnifiedBitPerfect = USE_UNIFIED_BIT_PERFECT_DEFAULT
        return USE_UNIFIED_BIT_PERFECT_DEFAULT
    }

    fun savePersistedUseUnifiedBitPerfect(context: Context, enabled: Boolean) {
        useUnifiedBitPerfect = enabled
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_USE_UNIFIED_BIT_PERFECT, enabled).apply()
    }

    fun loadPersistedSinkChain(context: Context): SinkChainMode {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (sp.contains(KEY_SINK_CHAIN)) {
            val mode = SinkChainMode.fromId(sp.getString(KEY_SINK_CHAIN, SinkChainMode.FULL.id))
            sinkChainMode = mode
            return mode
        }
        try {
            val dbFile = java.io.File(context.filesDir, "library.sqlite")
            if (dbFile.exists()) {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    db.rawQuery("SELECT value FROM prefs WHERE \"key\" = ?", arrayOf(KEY_SINK_CHAIN)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val mode = SinkChainMode.fromId(cursor.getString(0))
                            sp.edit().putString(KEY_SINK_CHAIN, mode.id).apply()
                            sinkChainMode = mode
                            return mode
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        sinkChainMode = SinkChainMode.FULL
        return SinkChainMode.FULL
    }

    fun savePersistedSinkChain(context: Context, mode: SinkChainMode) {
        sinkChainMode = mode
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_SINK_CHAIN, mode.id).apply()
    }

    fun loadPersistedUsbExclusiveMode(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        devApi34MixerEnabled = sp.getBoolean("dev_api34_bitperfect_mixer", false)
        devUsbAdpfEnabled = sp.getBoolean(KEY_DEV_USB_ADPF, false)
        devUsbSilentKeepAliveEnabled = sp.getBoolean(KEY_DEV_USB_SILENT_KEEPALIVE, false)
        allowEqInUsbExclusive = PlatformBitPerfectPrefs.loadAllowEqInUsbExclusive()
        loadPersistedUseUnifiedBitPerfect(context)
        if (useUnifiedBitPerfect) {
            val mode = PlatformBitPerfectPrefs.load()
            val v = mode == BitPerfectMode.ON
            usbExclusiveMode = v
            return v
        }
        if (sp.contains(KEY_USB_EXCLUSIVE)) {
            val v = sp.getBoolean(KEY_USB_EXCLUSIVE, false)
            usbExclusiveMode = v
            return v
        }
        // Fallback: check library.sqlite if present from previous sessions
        try {
            val dbFile = java.io.File(context.filesDir, "library.sqlite")
            if (dbFile.exists()) {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    db.rawQuery("SELECT value FROM prefs WHERE \"key\" = ?", arrayOf(KEY_USB_EXCLUSIVE)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val v = cursor.getString(0) == "true"
                            sp.edit().putBoolean(KEY_USB_EXCLUSIVE, v).apply()
                            usbExclusiveMode = v
                            return v
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        val defaultVal = false
        sp.edit().putBoolean(KEY_USB_EXCLUSIVE, defaultVal).apply()
        usbExclusiveMode = defaultVal
        return defaultVal
    }

    fun savePersistedUsbExclusiveMode(context: Context, enabled: Boolean) {
        usbExclusiveMode = enabled
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_USB_EXCLUSIVE, enabled).apply()
    }

    fun requestReconfiguration() {
        val player = activePlayer
        val looper = player?.applicationLooper ?: android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == looper) {
            onUsbReconfigurationRequested?.invoke()
        } else {
            android.os.Handler(looper).post {
                onUsbReconfigurationRequested?.invoke()
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context): ExoPlayer {
        val ctx = context.applicationContext
        val savedPref = loadPersistedUsbExclusiveMode(ctx)
        val savedChain = loadPersistedSinkChain(ctx)
        PlayLog.log("usb", "createPlayer: usbExclusiveMode initial = $savedPref (pref=$savedPref), sinkChain = ${savedChain.id}")
        io.max.paperplay.player.usb.UsbRingLog.record("PlaybackCore", "createPlayer: usbExclusiveMode initial = $savedPref (pref=$savedPref), sinkChain = ${savedChain.id}", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
        val up = upstream ?: DefaultDataSource.Factory(ctx)
        val factory = DataSource.Factory { RoutingDataSource(sources, StreamDataSource(sources), up.createDataSource()) }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(ctx, TapRenderersFactory(ctx))
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true, // audio focus
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        var currentWakeMode = C.WAKE_MODE_LOCAL
        fun updateWakeModeForItem(item: androidx.media3.common.MediaItem?) {
            val uriStr = item?.localConfiguration?.uri?.toString()
            val desired = if (isNetworkMediaUri(uriStr)) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
            if (desired != currentWakeMode) {
                currentWakeMode = desired
                p.setWakeMode(desired)
                val label = if (desired == C.WAKE_MODE_NETWORK) "WAKE_MODE_NETWORK" else "WAKE_MODE_LOCAL"
                PlayLog.log("usb", "PlaybackCore: switched ExoPlayer wakeMode=$label for uri=$uriStr")
                io.max.paperplay.player.usb.UsbPowerTelemetry.recordCustom("EXO_WAKE_MODE", "$label uri=$uriStr")
            }
        }
        p.addAnalyticsListener(PlaybackLogging.analytics)
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (activePlayer !== p) return
                updateWakeModeForItem(mediaItem)
                val id = mediaItem?.mediaId ?: "none"
                val reasonStr = when (reason) {
                    androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                    androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                    androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                    androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                    else -> "REASON_$reason"
                }
                io.max.paperplay.player.usb.UsbRingLog.record("Player", "TRACK_SWITCH mediaId=$id reason=$reasonStr", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (activePlayer !== p) return
                playerPlaybackState = playbackState
                playIntentTracker.onPlaybackStateChanged(playbackState, p.mediaItemCount)
                if (playbackState == androidx.media3.common.Player.STATE_BUFFERING || playbackState == androidx.media3.common.Player.STATE_READY) {
                    updateWakeModeForItem(p.currentMediaItem)
                }
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    playerHasError = false
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (activePlayer !== p) return
                playerPlayWhenReady = playWhenReady
                playIntentTracker.onPlayWhenReadyChanged(playWhenReady, reason)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (activePlayer !== p) return
                playerIsPlaying = isPlaying
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (activePlayer !== p) return
                playerHasError = true
            }
        })
        activePlayer = p
        return p
    }
}
