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
    val CMD_SET_NORMALIZE = SessionCommand(ACTION_SET_NORMALIZE, Bundle.EMPTY)
    val CMD_SET_EQUALIZER = SessionCommand(ACTION_SET_EQUALIZER, Bundle.EMPTY)

    @Volatile var appContext: Context? = null
    @Volatile var upstream: DataSource.Factory? = null
    @Volatile var eqGains: List<Float>? = null
    /** True while the Rust engine is in the audio path (set by the sink wrapper), so the output info tells the truth. */
    @Volatile var engineActive = false
    /** Extra bass in dB for the Rust engine, 0 when the equalizer is off. */
    @Volatile var bassDb = 0f
    /** Engine level mode: true = exact curve (lowered by its peak), false = loud. */
    @Volatile var exactLevel = false
    /** A/B: true plays the untouched input at the processed level. */
    @Volatile var dryCompare = false
    /** Stereo width above the bass, 1 = as recorded. */
    @Volatile var width = 1f
    /** What the Rust engine reads and writes, and its meters, for the output info. Set by the sink wrapper. */
    @Volatile var engineIn: String? = null
    @Volatile var engineOut: String? = null
    @Volatile var engineStats: FloatArray? = null
    @Volatile var normalize = false
    @Volatile var speed = 1f
    val sources = ConcurrentHashMap<String, StreamSource>()

    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context): ExoPlayer {
        val ctx = context.applicationContext
        val up = upstream ?: DefaultDataSource.Factory(ctx)
        val factory = DataSource.Factory { RoutingDataSource(sources, StreamDataSource(sources), up.createDataSource()) }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(ctx, TapRenderersFactory(ctx))
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
            .build()
    }
}
