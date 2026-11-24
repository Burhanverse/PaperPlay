package io.max.paperplay.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs
import kotlin.math.ln

/** Owns the [ExoPlayer], [Equalizer], and [MediaSession] + notification for background playback. */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var eq: Equalizer? = null
    private var leveller: DynamicsProcessing? = null
    private var loggingListener: Player.Listener? = null

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) { applyEqualizer(audioSessionId); applyNormalize(audioSessionId) }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.packageName == packageName) {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(PlaybackCore.CMD_SET_EQUALIZER)
                    .add(PlaybackCore.CMD_SET_NORMALIZE)
                    .add(PlaybackCore.CMD_SET_SINK_CHAIN)
                    .add(PlaybackCore.CMD_REBUILD_FOR_BIT_PERFECT)
                    .build()
                return MediaSession.ConnectionResult.accept(
                    sessionCommands,
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                )
            }
            return super.onConnect(session, controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == PlaybackCore.ACTION_SET_EQUALIZER && controller.packageName == packageName) {
                PlaybackCore.eqGains = args.getFloatArray(PlaybackCore.EXTRA_EQ_GAINS)?.toList()
                applyEqualizer(player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == PlaybackCore.ACTION_SET_NORMALIZE && controller.packageName == packageName) {
                PlaybackCore.normalize = args.getBoolean(PlaybackCore.EXTRA_NORMALIZE, false)
                applyNormalize(player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == PlaybackCore.ACTION_SET_SINK_CHAIN && controller.packageName == packageName) {
                val mode = SinkChainMode.fromId(args.getString(PlaybackCore.EXTRA_SINK_CHAIN))
                PlaybackCore.savePersistedSinkChain(this@PlaybackService, mode)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == PlaybackCore.ACTION_REBUILD_FOR_BIT_PERFECT && controller.packageName == packageName) {
                val explicit = args.getBoolean(PlaybackCore.EXTRA_REBUILD_EXPLICIT, true)
                scheduleRebuildForBitPerfect(explicit)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val savedPref = PlaybackCore.loadPersistedUsbExclusiveMode(this)
        val savedChain = PlaybackCore.loadPersistedSinkChain(this)
        val initialFlag = PlaybackCore.usbExclusiveMode
        PlaybackCore.usbExclusiveMode = savedPref
        PlayLog.log("usb", "PlaybackService: usbExclusiveMode initial = $savedPref (pref=$savedPref), sinkChain = ${savedChain.id}")
        if (initialFlag != savedPref) {
            PlayLog.log("usb", "WARNING: usbExclusiveMode initial mismatch in PlaybackService: flag=$initialFlag pref=$savedPref")
        }
        io.max.paperplay.player.usb.UsbRingLog.record("PlaybackService", "usbExclusiveMode initial = $savedPref (pref=$savedPref), sinkChain = ${savedChain.id}", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)

        // The media notification's small icon is the app's paper plane, not Media3's round play icon. The drawable
        // lives in the app module, so it is looked up by name.
        resources.getIdentifier("ic_stat_paperplay", "drawable", packageName).takeIf { it != 0 }?.let { icon ->
            val base = androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(icon) }
            // Posted ungrouped: as a child of Media3's group key with no summary the shade drew it as an empty card.
            setMediaNotificationProvider(object : androidx.media3.session.MediaNotification.Provider by base {
                override fun createNotification(
                    mediaSession: MediaSession,
                    mediaButtonPreferences: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
                    actionFactory: androidx.media3.session.MediaNotification.ActionFactory,
                    onNotificationChangedCallback: androidx.media3.session.MediaNotification.Provider.Callback,
                ): androidx.media3.session.MediaNotification {
                    val n = base.createNotification(mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback)
                    val plain = android.app.Notification.Builder.recoverBuilder(this@PlaybackService, n.notification).setGroup(null).build()
                    return androidx.media3.session.MediaNotification(n.notificationId, plain)
                }
            })
        }
        val exo = PlaybackCore.createPlayer(this)
        exo.addListener(playerListener)
        val logListener = PlaybackLogging.listener(exo)
        loggingListener = logListener
        exo.addListener(logListener)
        PlayLog.log("player", "service started")
        player = exo
        applyEqualizer(exo.audioSessionId)
        applyNormalize(exo.audioSessionId)

        val forwardingPlayer = io.max.paperplay.player.usb.UsbVolumeForwardingPlayer(exo, this)
        io.max.paperplay.player.usb.UsbAudioManager.instance?.forwardingPlayer = forwardingPlayer
        PlaybackCore.onRebuildPlayerForBitPerfect = { explicit ->
            scheduleRebuildForBitPerfect(explicit)
        }

        // A tap on the notification opens the app on the player.
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.putExtra("open", "player")
        val builder = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(sessionCallback)
        if (open != null) {
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    1,
                    open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        session = builder.build()
    }

    private val rebuildHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRebuildExplicit = false
    private val rebuildRunnable = Runnable {
        val explicit = pendingRebuildExplicit
        pendingRebuildExplicit = false
        rebuildPlayerForBitPerfect(explicit)
    }

    private fun scheduleRebuildForBitPerfect(explicit: Boolean = true) {
        if (explicit) pendingRebuildExplicit = true
        rebuildHandler.removeCallbacks(rebuildRunnable)
        rebuildHandler.postDelayed(rebuildRunnable, 150L)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        rebuildHandler.removeCallbacks(rebuildRunnable)
        pendingRebuildExplicit = false
        PlaybackCore.onRebuildPlayerForBitPerfect = null
        io.max.paperplay.player.usb.UsbAudioManager.instance?.forwardingPlayer = null
        io.max.paperplay.player.usb.UsbSilentAudioKeepAlive.stop()
        io.max.paperplay.player.usb.UsbStreamingWakeLock.releaseAll("PlaybackService.onDestroy")
        runCatching { eq?.release() }
        eq = null
        runCatching { leveller?.release() }
        leveller = null
        session?.release()
        session = null
        player?.removeListener(playerListener)
        loggingListener?.let { player?.removeListener(it) }
        loggingListener = null
        player?.release()
        player = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private fun wrapExoAdapter(exo: ExoPlayer): io.max.paperplay.player.usb.RebuildPlayerAdapter<androidx.media3.common.MediaItem, androidx.media3.common.PlaybackParameters> =
        object : io.max.paperplay.player.usb.RebuildPlayerAdapter<androidx.media3.common.MediaItem, androidx.media3.common.PlaybackParameters> {
            override val itemCount: Int get() = exo.mediaItemCount
            override val currentIndex: Int get() = exo.currentMediaItemIndex
            override val positionMs: Long get() = exo.currentPosition
            override val playbackState: Int get() = exo.playbackState
            override val playWhenReady: Boolean get() = exo.playWhenReady
            override val isPlaying: Boolean get() = exo.isPlaying
            override val playbackSuppressionReason: Int get() = exo.playbackSuppressionReason
            override var repeatMode: Int
                get() = exo.repeatMode
                set(value) { exo.repeatMode = value }
            override var shuffleModeEnabled: Boolean
                get() = exo.shuffleModeEnabled
                set(value) { exo.shuffleModeEnabled = value }
            override var playbackParameters: androidx.media3.common.PlaybackParameters
                get() = exo.playbackParameters
                set(value) { exo.playbackParameters = value }
            override fun getItemAt(index: Int): androidx.media3.common.MediaItem = exo.getMediaItemAt(index)
            override fun setMediaItems(items: List<androidx.media3.common.MediaItem>, startIndex: Int, startPositionMs: Long) {
                exo.setMediaItems(items, startIndex, startPositionMs)
            }
            override fun setPlayWhenReady(playWhenReady: Boolean) { exo.playWhenReady = playWhenReady }
            override fun prepare() { exo.prepare() }
            override fun detachListeners() {
                exo.removeListener(playerListener)
                loggingListener?.let { exo.removeListener(it) }
            }
            override fun release() { exo.release() }
        }

    /**
     * Rebuilds ExoPlayer when Bit-perfect switches between ON and OFF while preserving
     * current media items, queue index, playback position, user play intent, repeat, shuffle,
     * playback speed, and MediaSession continuity.
     */
    @OptIn(UnstableApi::class)
    private fun rebuildPlayerForBitPerfect(explicit: Boolean) {
        val old = player ?: return
        val shouldRebuild = io.max.paperplay.player.usb.BitPerfectRebuildPolicy.shouldRebuildPlayer(
            io.max.paperplay.player.usb.SinkRebuildNeedInput(
                isExplicitModeOrChainChange = explicit,
                usbExclusiveMode = PlaybackCore.usbExclusiveMode,
                useUnifiedBitPerfect = PlaybackCore.useUnifiedBitPerfect,
                devApi34MixerEnabled = PlaybackCore.devApi34MixerEnabled,
                hasUnifiedUsbSinkInstance = io.max.paperplay.player.usb.UnifiedUsbAudioSink.currentInstance != null,
                api34ActiveOnCurrentPlayer = io.max.paperplay.player.usb.Api34BitPerfectMixer.lastVerification is io.max.paperplay.player.usb.Api34MixerVerification.Verified,
            ),
        )
        if (!shouldRebuild) {
            PlayLog.log("usb", "PlaybackService: skipping rebuild (not needed, explicit=$explicit)")
            return
        }

        val createdExos = mutableListOf<ExoPlayer>()
        var newForwarding: io.max.paperplay.player.usb.UsbVolumeForwardingPlayer? = null
        val (_, plan) = io.max.paperplay.player.usb.PlaybackRebuildTransaction.execute(
            old = wrapExoAdapter(old),
            userPlayIntent = PlaybackCore.userPlayIntent,
            createNext = {
                val next = PlaybackCore.createPlayer(this)
                next.addListener(playerListener)
                val logListener = PlaybackLogging.listener(next)
                loggingListener = logListener
                next.addListener(logListener)
                createdExos.add(next)
                wrapExoAdapter(next)
            },
            wrapForwarding = {
                val nextExo = createdExos.last()
                io.max.paperplay.player.usb.UsbVolumeForwardingPlayer(nextExo, this).also { newForwarding = it }
            },
            session = object : io.max.paperplay.player.usb.SessionSwapAdapter<io.max.paperplay.player.usb.UsbVolumeForwardingPlayer> {
                override fun swapSessionPlayer(newPlayer: io.max.paperplay.player.usb.UsbVolumeForwardingPlayer) {
                    val nextExo = createdExos.last()
                    player = nextExo
                    io.max.paperplay.player.usb.UsbAudioManager.instance?.forwardingPlayer = newPlayer
                    session?.player = newPlayer
                }
            },
            onAfterPrepare = { _, p ->
                val nextExo = createdExos.last()
                applyEqualizer(nextExo.audioSessionId)
                applyNormalize(nextExo.audioSessionId)
                PlaybackCore.playIntentTracker.reset(p.targetPlayWhenReady)
                PlaybackCore.playerPlayWhenReady = p.targetPlayWhenReady
                PlaybackCore.playerPlaybackState = nextExo.playbackState
                PlaybackCore.playerIsPlaying = false
                newForwarding?.notifyDeviceInfoChanged()
            },
        )
        PlayLog.log(
            "usb",
            "PlaybackService: rebuilt player (explicit=$explicit, reason=${plan.reason}, setItems=${plan.shouldSetItems}, prepare=${plan.shouldPrepareQueue}, targetPlay=${plan.targetPlayWhenReady}, idx=${plan.safeIndex}, pos=${plan.safePositionMs})",
        )
    }

    /** A limiter that pulls loud peaks down and lifts the rest, so songs mastered at different levels sound alike. Needs Android 9. */
    private fun applyNormalize(audioSessionId: Int) {
        runCatching { leveller?.release() }
        leveller = null
        if (!PlaybackCore.normalize || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2, false, 0, false, 0, false, 0, true,
            ).build()
            val dp = DynamicsProcessing(0, audioSessionId, config)
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 5f, 80f, 6f, -14f, 6f))
            dp.enabled = true
            leveller = dp
        }
    }

    /** Device equalizers have a handful of bands, so each reads the curve through [EQ_BANDS_HZ] at its own frequency. */
    private fun applyEqualizer(audioSessionId: Int) {
        runCatching { eq?.release() }
        eq = null
        if (EngineNative.available) return // the Rust engine in the audio chain does the equalizing
        val gains = PlaybackCore.eqGains ?: return
        if (gains.all { abs(it) < 0.05f }) return
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        runCatching {
            val e = Equalizer(0, audioSessionId)
            val range = e.bandLevelRange
            for (b in 0 until e.numberOfBands) {
                val hz = e.getCenterFreq(b.toShort()) / 1000.0
                if (hz <= 0.0) continue
                val level = (eqGainAt(gains, hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                e.setBandLevel(b.toShort(), level.toShort())
            }
            e.enabled = true
            eq = e
        }
    }
}
