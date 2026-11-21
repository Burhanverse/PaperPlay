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
                rebuildPlayerForBitPerfect()
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
        exo.addListener(PlaybackLogging.listener(exo))
        exo.addAnalyticsListener(PlaybackLogging.analytics)
        PlayLog.log("player", "service started")
        player = exo
        applyEqualizer(exo.audioSessionId)
        applyNormalize(exo.audioSessionId)

        val forwardingPlayer = io.max.paperplay.player.usb.UsbVolumeForwardingPlayer(exo, this)
        io.max.paperplay.player.usb.UsbAudioManager.instance?.forwardingPlayer = forwardingPlayer
        PlaybackCore.onRebuildPlayerForBitPerfect = {
            android.os.Handler(mainLooper).post { rebuildPlayerForBitPerfect() }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        PlaybackCore.onRebuildPlayerForBitPerfect = null
        io.max.paperplay.player.usb.UsbAudioManager.instance?.forwardingPlayer = null
        runCatching { eq?.release() }
        eq = null
        runCatching { leveller?.release() }
        leveller = null
        session?.release()
        session = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Rebuilds ExoPlayer when Bit-perfect switches between ON and OFF while preserving
     * current media items, queue index, playback position, playWhenReady, repeat, shuffle,
     * playback speed, and MediaSession continuity.
     */
    @OptIn(UnstableApi::class)
    private fun rebuildPlayerForBitPerfect() {
        val old = player ?: return
        val itemCount = old.mediaItemCount
        val items = (0 until itemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val posMs = old.currentPosition.coerceAtLeast(0L)
        val playWhenReady = old.playWhenReady
        val repeatMode = old.repeatMode
        val shuffleEnabled = old.shuffleModeEnabled
        val playbackParams = old.playbackParameters

        old.removeListener(playerListener)
        old.release()

        val next = PlaybackCore.createPlayer(this)
        next.addListener(playerListener)
        next.addListener(PlaybackLogging.listener(next))
        next.addAnalyticsListener(PlaybackLogging.analytics)
        player = next
        applyEqualizer(next.audioSessionId)
        applyNormalize(next.audioSessionId)

        val newForwardingPlayer = io.max.paperplay.player.usb.UsbVolumeForwardingPlayer(next, this)
        io.max.paperplay.player.usb.UsbAudioManager.instance?.forwardingPlayer = newForwardingPlayer
        session?.player = newForwardingPlayer

        next.repeatMode = repeatMode
        next.shuffleModeEnabled = shuffleEnabled
        next.playbackParameters = playbackParams
        if (items.isNotEmpty()) {
            val safeIdx = if (index in items.indices) index else 0
            next.setMediaItems(items, safeIdx, posMs)
            next.prepare()
        }
        next.playWhenReady = playWhenReady
        PlayLog.log("usb", "PlaybackService: rebuilt player (unified=${PlaybackCore.useUnifiedBitPerfect}, exclusive=${PlaybackCore.usbExclusiveMode}, items=${items.size}, idx=$index, pos=$posMs)")
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
