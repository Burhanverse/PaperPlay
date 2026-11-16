package io.max.paperplay.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/** Writes what the ExoPlayer does to [PlayLog]: state, position and duration at every change, decoder, errors, underruns. */
@OptIn(UnstableApi::class)
internal object PlaybackLogging {
    private fun state(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "$s"
    }

    private fun transition(r: Int) = when (r) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO (song ended)"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        else -> "$r"
    }

    private fun discontinuity(r: Int) = when (r) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
        Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
        Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
        Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
        Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
        else -> "$r"
    }

    private fun why(r: Int) = when (r) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "headphones unplugged"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote control"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of song"
        else -> "$r"
    }

    private fun fmt(f: Format?) = if (f == null) "none" else
        "${f.sampleMimeType} codecs=${f.codecs} ${f.sampleRate}Hz ${f.channelCount}ch bitrate=${f.bitrate} pcm=${f.pcmEncoding}"

    private fun at(p: Player) = "pos=${p.currentPosition}ms dur=${p.duration.takeIf { it != C.TIME_UNSET } ?: -1}ms buffered=${p.bufferedPosition}ms item=${p.currentMediaItemIndex}/${p.mediaItemCount}"

    fun listener(p: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            PlayLog.log("player", "state ${state(playbackState)} ${at(p)} playWhenReady=${p.playWhenReady}")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            PlayLog.log("player", "playWhenReady=$playWhenReady because ${why(reason)} ${at(p)}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlayLog.log("player", "isPlaying=$isPlaying ${at(p)}")
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            PlayLog.log("player", "item change ${transition(reason)} to ${mediaItem?.mediaId} ${at(p)}")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            PlayLog.log(
                "player",
                "jump ${discontinuity(reason)} from item ${oldPosition.mediaItemIndex} at ${oldPosition.positionMs}ms to item ${newPosition.mediaItemIndex} at ${newPosition.positionMs}ms",
            )
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            PlayLog.log("player", "suppression reason=$playbackSuppressionReason ${at(p)}")
        }

        override fun onPlayerError(error: PlaybackException) {
            val chain = generateSequence<Throwable>(error) { it.cause }.joinToString(" <- ") { "${it::class.java.simpleName}: ${it.message}" }
            PlayLog.log("player", "ERROR ${error.errorCodeName} ($chain) ${at(p)}\n" + error.stackTraceToString())
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            PlayLog.log("player", "queue changed: ${timeline.windowCount} items, reason=$reason ${at(p)}")
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error == null) PlayLog.log("player", "error cleared")
        }
    }

    val analytics = object : AnalyticsListener {
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            PlayLog.log("decoder", "audio decoder $decoderName ready in ${initializationDurationMs}ms")
        }

        // What the sound card is really given: 16-bit here for a 24-bit file would mean the sink cut the depth.
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
            val enc = when (audioTrackConfig.encoding) { 2 -> "PCM 16 bit"; 4 -> "PCM float"; 21 -> "PCM 24 bit"; 22 -> "PCM 32 bit"; else -> "encoding ${audioTrackConfig.encoding}" }
            PlayLog.log("decoder", "audio output $enc, ${audioTrackConfig.sampleRate} Hz, channel mask ${audioTrackConfig.channelConfig}, buffer ${audioTrackConfig.bufferSize} bytes")
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            PlayLog.log("decoder", "input ${fmt(format)}")
        }

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            PlayLog.log("decoder", "audio underrun: buffer=${bufferSizeMs}ms, ${elapsedSinceLastFeedMs}ms since the last data")
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            PlayLog.log("decoder", "codec error: ${audioCodecError.stackTraceToString()}")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            PlayLog.log("decoder", "audio sink error: ${audioSinkError.stackTraceToString()}")
        }

        override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
            PlayLog.log("load", "load error (canceled=$wasCanceled) ${error::class.java.simpleName}: ${error.message}")
        }

        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            PlayLog.log("load", "loaded ${loadEventInfo.bytesLoaded} bytes in ${loadEventInfo.loadDurationMs}ms (type ${mediaLoadData.dataType})")
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {}
    }
}
