package io.max.paperplay.player.usb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore

/**
 * Experiment flag (default OFF): silent low-latency `MODE_STATIC` looped `AudioTrack` keep-alive
 * while USB exclusive streaming is active. Uses 0 extra threads and 0-amplitude PCM16 silence so
 * AudioFlinger/PowerHAL keeps low-latency audio power floors engaged for A/B measurement.
 */
internal object UsbSilentAudioKeepAlive {
    private const val SAMPLE_RATE = 48_000
    private const val LOOP_FRAMES = 480 // 10 ms silent static buffer
    private val lock = Any()
    private var track: AudioTrack? = null

    val isActive: Boolean
        get() = synchronized(lock) { track != null }

    fun setActive(active: Boolean) {
        if (active && PlaybackCore.devUsbSilentKeepAliveEnabled) {
            start()
        } else {
            stop()
        }
    }

    fun start() {
        synchronized(lock) {
            if (track != null || !PlaybackCore.devUsbSilentKeepAliveEnabled) return
            val bufBytes = LOOP_FRAMES * 2 * 2 // stereo 16-bit PCM
            runCatching {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                val t = builder.build()
                val zeros = ByteArray(bufBytes)
                t.write(zeros, 0, zeros.size)
                t.setLoopPoints(0, LOOP_FRAMES, -1)
                t.setVolume(0f)
                t.play()
                track = t
                PlayLog.log("usb", "UsbSilentAudioKeepAlive: started silent low-latency AudioTrack")
                UsbPowerTelemetry.recordCustom("SILENT_KEEPALIVE", "started")
            }.onFailure {
                PlayLog.log("usb", "UsbSilentAudioKeepAlive: start failed: ${it.message}")
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val t = track ?: return
            track = null
            runCatching {
                t.pause()
                t.stop()
                t.release()
                PlayLog.log("usb", "UsbSilentAudioKeepAlive: stopped")
                UsbPowerTelemetry.recordCustom("SILENT_KEEPALIVE", "stopped")
            }.onFailure {
                PlayLog.log("usb", "UsbSilentAudioKeepAlive: stop failed: ${it.message}")
            }
        }
    }
}
